package org.freenetproject.routing_simulator.graph;

import org.freenetproject.routing_simulator.graph.degree.FixedDegreeSource;
import org.freenetproject.routing_simulator.graph.degree.PoissonDegreeSource;
import org.freenetproject.routing_simulator.graph.linklength.LinkLengthSource;
import org.freenetproject.routing_simulator.graph.degree.DegreeSource;
import org.freenetproject.routing_simulator.graph.node.SimpleNode;
import org.freenetproject.routing_simulator.util.ArrayStats;
import org.freenetproject.routing_simulator.util.MersenneTwister;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Class to represent and generate graphs of small world networks.
 * At present only limited Kleinberg graphs are generated.
 * Functions to evaluate the graph topology are also provided.
 */
public class Graph {
	private ArrayList<SimpleNode> nodes;
	private double[] locations;
	/**
	 * Probability of not making a connection with a peer which has its desired degree.
	 */
	private static final double rejectProbability = 0.98;

	/**
	 * Private constructor; call one of the generator functions instead.
	 *
	 * @param nNodes Initial internal capacity.  The actual graph may have
	 * more or fewer nodes.
	 */
	private Graph(int nNodes) {
		if (nNodes <= 0)
			throw new IllegalArgumentException("Must have positive nodes.");
		nodes = new ArrayList<SimpleNode>(nNodes);
		locations = null;
	}

	private void generateNodes(GraphParam param, Random rand, DegreeSource source) {
		locations = new double[param.n];
		if (param.fastGeneration) {
			for (int i = 0; i < param.n; i++) locations[i] = (1.0 * i) / param.n;
		} else {
			for (int i = 0; i < param.n; i++) locations[i] = rand.nextDouble();
		}

		//TODO: Reason to sort if not fastGeneration?
		Arrays.sort(locations);
		for (int i = 0; i < param.n; i++) {
			//TODO: index in constructor
			SimpleNode node = new SimpleNode(locations[i], rand, source.getDegree());
			node.index = i;
			nodes.add(node);
		}

	}

	private static class DistanceEntry implements Comparable<DistanceEntry> {
		public final double distance;
		public final int index;

		public DistanceEntry(double distance, int index) {
			this.distance = distance;
			this.index = index;
		}

		@Override
		public int compareTo(DistanceEntry other) {
			return Double.compare(this.distance, other.distance);
		}
	}

	public static Graph generateSandberg(GraphParam param, Random rand) {
		Graph g = new Graph(param.n);

		DegreeSource source = new FixedDegreeSource(1337);
		g.generateNodes(param, rand, source);

		// Base graph: Edge from X to X - 1 mod N for all nodes 0 to N - 1.
		for (int i = 0; i < param.n; i++) {
			// Modulo of negative not defined: manually wrap.
			int wrapped = i - 1;
			if (wrapped < 0) wrapped += param.n;
			g.getNode(i).connectOutgoing(g.getNode(wrapped));
		}

		// Shortcuts: Edges from each node to... TODO: random endpoint?
		int other;
		for (int i = 0; i < param.n; i++) {
			do {
				other = rand.nextInt(param.n);
			} while (other == i || g.getNode(i).isConnected(g.getNode(other)));
			g.getNode(i).connectOutgoing(g.getNode(other));
		}

		return g;
	}

	//TODO: Using GraphParam as an argument is beginning to smell: this takes additional arguments and ignores the number of close connections.
	/**
	 * Generates a graph with link length distribution and peer count distribution as described in the given sources.
	 * @param param graph generation parameters. Local and remote connections irrelevant as given distribution is followed.
	 * @param rand used for random numbers
	 * @return specified graph
	 */
	public static Graph generateGraph(GraphParam param, Random rand, DegreeSource degreeSource, LinkLengthSource linkLengthSource) {
		Graph g = new Graph(param.n);
		g.generateNodes(param, rand, degreeSource);

		DistanceEntry[] distances = new DistanceEntry[param.n];
		for (int i = 0; i < param.n; i++) {
			SimpleNode src = g.nodes.get(i);
			if (src.atDegree()) continue;
			SimpleNode dest;

				// Fill distance entry array.
				for (int j = 0; j < param.n; j++) {
					distances[j] = new DistanceEntry(Location.distance(src.getLocation(), g.nodes.get(j).getLocation()), j);
				}

				//System.out.println("distances size is " + );
				Arrays.sort(distances);

				// Make connections until at desired degree.
				while (!src.atDegree()) {
					double length = linkLengthSource.getLinkLength(rand);
					int idx = Arrays.binarySearch(distances, new DistanceEntry(length, -1));
					if (idx < 0) idx = -1 - idx;
					if (idx >= param.n) idx = param.n - 1;
					dest = g.nodes.get(distances[idx].index);
					if (src == dest || src.isConnected(dest) ||
					    (dest.atDegree() && rand.nextDouble() < rejectProbability)) continue;
					src.connect(dest);
				}
		}

		return g;
	}

	/**
	 * Generate a one-dimensional Kleinberg Graph with given parameters.
	 * See The Small-World Phenomenon: An Algorithmic Perspective
	 * Jon Kleinberg, 1999
	 * http://www.cs.cornell.edu/home/kleinber/swn.pdf
	 * We use a modified version where edges are not directed.
	 * Note that q specifies outgoing links, and p is distance not link
	 * count.  Average node degree will be 2 * (p + q), minimum node
	 * degree 2 * p + q.  There is no maximum node degree.
	 * TODO: Adjacent link support not yet tested.
	 *
	 * @param param Contains graph parameters such as size and number of various-distance connections.
	 * @param rand Random number source used for initialization: locations and probabilities.
	 * @return A Graph with the desired structure
	 */
	public static Graph generate1dKleinbergGraph(GraphParam param, Random rand, DegreeSource source) {
		//TODO: Arguments list is cleaner, but this is a mess.
		final int n = param.n;
		final boolean fastGeneration = param.fastGeneration;

		Graph g = new Graph(n);

		//make nodes
		g.generateNodes(param, rand, source);

		//make far links
		double[] sumProb = new double[n];
		for (int i = 0; i < n; i++) {
			SimpleNode src = g.nodes.get(i);
			SimpleNode dest;
			if (fastGeneration) {
				//Continuous approximation to 1/d distribution; accurate in the large n case.
				//Treats spacing as even, whether or not that is accurate.
				//Assumes nodes are sorted in location order.
				double maxSteps = n / 2.0;
				while (!src.atDegree()) {
					/*
					 * The array is sorted by location and evenly spaced, so a change in index goes
					 * a consistent distance away.
					 */
					int steps = (int)Math.round(Math.pow(maxSteps, rand.nextDouble()));
					assert steps >= 0 && steps <= n / 2;
					int idx = rand.nextBoolean() ? i + steps : i - steps;
					if (idx < 0) idx += n;
					if (idx >= n) idx -= n;
					dest = g.nodes.get(idx);
					if (idx == i || src.isConnected(dest)
					    || (dest.atDegree() && rand.nextDouble() < rejectProbability)) {
						continue;
					}
					src.connect(dest);
				}
			} else {
				//Slow generation operates on exact node locations (even or otherwise).
				//Does not require sorted node order.
				//Precisely accurate even with uneven locations and small graph size.

				/*
				 * Find normalizing constant for this node - sum distance probabilities so that they
				 * they are in increasing order and may be searched through to find the closest link.
				 * Note that this means here the probability is proportional to 1/distance.
				 * sumProb is a non-normalized CDF of probabilities by node index.
				 */
				double norm = 0.0;
				for (int j = 0; j < n; j++) {
					if (i != j) {
						norm += 1.0 / Location.distance(src.getLocation(), g.nodes.get(j).getLocation());
					}
					sumProb[j] = norm;
					//CDF must be non-decreasing
					if (j > 0) assert sumProb[j] >= sumProb[j-1];
				}

				while (!src.atDegree()) {
					/*
					 * sumProb is a CDF, so to weight by it pick a "Y value" and find closest index.
					 * norm is now the highest (and last) value in the CDF, so this is picking
					 * a distance probability sum and finding the closest node for that distance.
					 * Because there are more nodes which match values in highly represented domains
					 * (steeper in the CDF) a random value is more likely to be in those areas.
					 */
					double x = rand.nextDouble() * norm;
					assert x <= norm;
					int idx = Arrays.binarySearch(sumProb, x);
					/*
					 * If such value is not actually present, as it might not be due to being
					 * floating point, use the index where it would be inserted:
					 * idx = -insertion point - 1
					 * insertion point = -1 - idx
					 * The insertion point would be the length of the array and thus out of bounds
					 * if all elements were less than it, but this will not happen as norm is the
					 * greatest element and nextDouble() is [0, 1). This does not mean it will not
					 * choose the greatest element as insertion point is the index of the first
					 * greater element.
					 * TODO: Does this result in choosing the greater value when the lesser is closer? Looks like it yes.
					 */
					if (idx < 0) idx = -1 - idx;
					//idx is index of the first greater element, but use the lesser if it is closer.
					if (idx > 0 && Math.abs(x - sumProb[idx - 1]) < Math.abs(x - sumProb[idx])) idx--;
					//Assert that this actually is the closest.
					if (idx > 0) assert Math.abs(x - sumProb[idx]) < Math.abs(x - sumProb[idx - 1]);
					if (idx < sumProb.length - 1) assert Math.abs(x - sumProb[idx]) < Math.abs(x - sumProb[idx + 1]);
					dest = g.nodes.get(idx);
					if (src == dest || src.isConnected(dest)
					    || (dest.atDegree() && rand.nextDouble() < rejectProbability)) {
						continue;
					}
					src.connect(dest);
				}
			}
		}

		return g;
	}

	/**
	 * Writes graph to a file. Format:
	 * <ul>
	 *      <li>Number of nodes.</li>
	 *      <li>Serialized SimpleNodes.</li>
	 * </ul>
	 * @param destination file to write graph to.
	 */
	public void write(File destination) {
		try {
			final FileOutputStream outputStream = new FileOutputStream(destination);
			final ObjectOutputStream output = new ObjectOutputStream(outputStream);

			// Number of nodes.
			output.writeInt(nodes.size());

			// Nodes.
			for (SimpleNode node : nodes) output.writeObject(node);

			/*
			 * Write connections starting from zero index to higher index nodes. This way each connection
			 * will only be written once: the other end at a higher index will not write the connection to
			 * the lower node, which has already been written.
			 *
			 * Add to intermediate list first in order to be able to write the number of connections for the
			 * purposes of reading more easily.
			 * TODO: Better to read pairs until error? Less extensible.
			 */
			final ArrayList<Integer> connectionIndexes = new ArrayList<Integer>();
			int writtenConnections = 0;
			for (int i = 0; i < nodes.size(); i++) {
				for (SimpleNode node: nodes.get(i).getConnections()) {
					if (node.index > i) {
						writtenConnections++;
						connectionIndexes.add(i);
						connectionIndexes.add(node.index);
					}
				}
			}

			output.writeInt(writtenConnections);
			System.out.println("Writing " + writtenConnections + " connections.");
			assert connectionIndexes.size() == writtenConnections * 2;
			for (Integer index : connectionIndexes) {
				output.writeInt(index);
			}

			output.flush();
			outputStream.close();
		} catch (IOException e) {
			System.err.println("Could not write to " + destination.getAbsolutePath() + ":");
			e.printStackTrace();
			System.exit(3);
		}
	}

	/**
	 * Constructs the graph from a file.
	 * @param source file to read the graph from.
	 * @param random Randomness source to give to nodes.
	 * @return graph defined by the file.
	 */
	public static Graph read(File source, Random random) {
		try {
			final FileInputStream inputStream = new FileInputStream(source);
			final ObjectInputStream input = new ObjectInputStream(inputStream);

			// Number of nodes.
			final int networkSize = input.readInt();
			final Graph graph = new Graph(networkSize);
			graph.locations = new double[networkSize];

			// Nodes.
			for (int i = 0; i < networkSize; i++) {
				SimpleNode node = (SimpleNode)input.readObject();
				node.setRand(random);
				node.index = i;
				graph.locations[i] = node.getLocation();
				graph.nodes.add(node);
			}

			final int writtenConnections = input.readInt();
			System.out.println("Reading " + writtenConnections + " connections.");
			//Each connection consists of two indexes in a pair.
			for (int i = 0; i < writtenConnections; i++) {
				final int from = input.readInt();
				final int to = input.readInt();
				//System.out.println(from + " " + to);
				graph.nodes.get(from).connect(graph.nodes.get(to));
			}

			return graph;
		} catch (IOException e) {
			System.err.println("Could not read from " + source.getAbsolutePath() + ":");
			e.printStackTrace();
			System.exit(4);
			return null;
		} catch (ClassNotFoundException e) {
			System.err.println("Unexpected class in graph:");
			e.printStackTrace();
			System.exit(5);
			return null;
		}
	}

	/**
	 * Get a node by index.
	 *
	 * @param i Index of node to get
	 * @return Node at index i
	 */
	public SimpleNode getNode(int i) {
		return nodes.get(i);
	}

	/**
	 * Print some topology statistics.
	 */
	public void printGraphStats(boolean verbose) {
		if (verbose) {
			int nEdges = nEdges();
			double meanDegree = ((double)(2 * nEdges)) / nodes.size();
			System.out.println(	"Graph stats:");
			System.out.println(	"Size:					" + size());
			System.out.println(	"Edges:					" + nEdges);
			System.out.println(	"Min degree:				" + minDegree());
			System.out.println(	"Max degree:				" + maxDegree());
			System.out.println(	"Mean degree:				" + meanDegree);
			System.out.println(	"Degree stddev:				" + Math.sqrt(degreeVariance()));
			System.out.println(	"Mean local clustering coefficient:	" + meanLocalClusterCoeff());
			System.out.println(	"Global clustering coefficient:		" + globalClusterCoeff());
			System.out.println();
		} else {
			double[] cc = localClusterCoeff();
			int[] deg = degrees();
			ArrayStats ccStats = new ArrayStats(cc);
			ArrayStats degStats = new ArrayStats(deg);
			System.out.print(size() + "\t" + nEdges() + "\t" + minDegree() + "\t" + maxDegree() + "\t" + globalClusterCoeff() + "\t");
			System.out.print(ccStats.mean() + "\t" + ccStats.stdDev() + "\t" + ccStats.skewness() + "\t" + ccStats.kurtosis() + "\t");
			System.out.print(degStats.mean() + "\t" + degStats.stdDev() + "\t" + degStats.skewness() + "\t" + degStats.kurtosis() + "\t");
		}
	}

	/**Print column headers for printGraphStats(false).*/
	public static void printGraphStatsHeader() {
		System.out.print("nNodes\tnEdges\tminDegree\tmaxDegree\tglobalClusterCoeff\tlocalCCMean\tlocalCCStdDev\tlocalCCSkew\tlocalCCKurtosis\t");
		System.out.print("degreeMean\tdegreeStdDev\tdegreeSkew\tdegreeKurtosis\t");
	}

	/**Get the topology stats as an array.*/
	public double[] graphStats() {
		double[] cc = localClusterCoeff();
		int[] deg = degrees();
		ArrayStats ccStats = new ArrayStats(cc);
		ArrayStats degStats = new ArrayStats(deg);
		return new double[] {size(), nEdges(), minDegree(), maxDegree(), globalClusterCoeff(),
			ccStats.mean(), ccStats.stdDev(), ccStats.skewness(), ccStats.kurtosis(),
			degStats.mean(), degStats.stdDev(), degStats.skewness(), degStats.kurtosis(),
		};
	}

	/**Edge length distribution*/
	public double[] edgeLengths() {
		int nEdges = nEdges();
		double[] lengths = new double[nEdges];
		int e = 0;
		for (int i = 0; i < nodes.size(); i++) {
			SimpleNode node = nodes.get(i);
			assert node.index == i;
			ArrayList<SimpleNode> conn = node.getConnections();
			for (int j = 0; j < conn.size(); j++) {
				if (conn.get(j).index < i) continue;
				assert conn.get(j).index != i;
				lengths[e++] = node.distanceToLoc(conn.get(j).getLocation());
			}
		}
		assert e == nEdges;
		return lengths;
	}

	/**
	 * Get the number of nodes in this graph.
	 *
	 * @return Size of the graph
	 */
	public int size() {
		return nodes.size();
	}

	/**
	 * Count edges in this graph.
	 *
	 * @return Total number of edges
	 */
	public int nEdges() {
		int nEdges = 0;
		for (int i = 0; i < nodes.size(); i++) {
			nEdges += nodes.get(i).degree();
		}

		//edges are undirected and will be double counted
		assert nEdges % 2 == 0;
		return nEdges / 2;
	}

	/**
	 * Find the minimum degree of any node in the graph.
	 *
	 * @return Minimum node degree
	 */
	public int minDegree() {
		int n = size();
		if (n == 0) return 0;
		int min = nodes.get(0).degree();
		for (int i = 1; i < n; i++) {
			min = Math.min(min, nodes.get(i).degree());
		}
		return min;
	}

	/**
	 * Find the maximum degree of any node in the graph.
	 *
	 * @return Maximum node degree
	 */
	public int maxDegree() {
		int n = size();
		if (n == 0) return 0;
		int max = nodes.get(0).degree();
		for (int i = 1; i < n; i++) {
			max = Math.max(max, nodes.get(i).degree());
		}
		return max;
	}

	/**
	 * Compute the variance in the degree of the nodes.
	 *
	 * @return Variance of node degree
	 */
	public double degreeVariance() {
		long sumDegrees = 0;
		long sumSquareDegrees = 0;
		long n = nodes.size();
		if (n == 0) return 0;
		for (int i = 0; i < n; i++) {
			int d = nodes.get(i).degree();
			sumDegrees += d;
			sumSquareDegrees += d * d;
		}

		double var = ((double)sumSquareDegrees)/((double)n) - ((double)(sumDegrees * sumDegrees))/((double)(n * n));
		return var;
	}


	/**
	 * Calculate the mean clustering coefficients of nodes in the graph.
	 * See http://en.wikipedia.org/wiki/Clustering_coefficient
	 * This is *not* the same as the global clustering coefficient
	 * described there; this is the unweighted mean of the local
	 * coefficients, which gives undue weight to low-degree nodes.
	 *
	 * @return Mean local clustering coefficient
	 */
	public double meanLocalClusterCoeff() {
		double sumCoeff = 0.0;
		int n = nodes.size();
		if (n == 0) return 0;
		for (int i = 0; i < n; i++) {
			sumCoeff += nodes.get(i).localClusterCoeff();
		}
		double mean = sumCoeff / n;
		assert mean >= 0.0 && mean <= 1.0;
		return mean;
	}

	public double[] localClusterCoeff() {
		int n = nodes.size();
		double[] cc = new double[n];
		for (int i = 0; i < n; i++) cc[i] = nodes.get(i).localClusterCoeff();
		return cc;
	}

	public int[] degrees() {
		int n = nodes.size();
		int[] d = new int[n];
		for (int i = 0; i < n; i++) d[i] = nodes.get(i).degree();
		return d;
	}

	/**
	 * Calculate the global clustering coefficient.
	 * See http://en.wikipedia.org/wiki/Clustering_coefficient
	 *
	 * @return Global clustering coefficient
	 */
	public double globalClusterCoeff() {
		int nClosed = 0;
		int nTotal = 0;

		for (int i = 0; i < nodes.size(); i++) {
			SimpleNode n = nodes.get(i);
			int degree = n.degree();
			nClosed += n.closedTriplets();
			nTotal += (degree * (degree - 1)) / 2;
		}

		return ((double)(nClosed)) / ((double)(nTotal));
	}

	/**
	 * Perform the darknet location swapping algorithm repeatedly.
	 *
	 * @param nAttempts Number of swaps to attempt
	 * @param uniform Whether to use centralized uniform probabilities or decentralized walks
	 * @param walkDist Number of hops to walk if using decentralized walks
	 * @param uniformWalk Whether to walk uniformly or attempt to correct for high degree node bias
	 * @param rand Randomness source
	 * @return Number of swap requests accepted
	 */
	public int darknetSwap(int nAttempts, boolean uniform, int walkDist, boolean uniformWalk, Random rand) {
		int nAccepted = 0;
		for (int i = 0; i < nAttempts; i++) {
			SimpleNode origin;
			SimpleNode target;
			origin = nodes.get(rand.nextInt(nodes.size()));
			if (uniform) {
				//centralized uniform swapping -- choose both nodes from a flat distribution
				target = nodes.get(rand.nextInt(nodes.size()));
			} else {
				//decentralized random walk
				int hops = walkDist;
				if (!uniformWalk) hops *= 2;	//correct for fact that some hops get rejected
				target = origin.randomWalk(hops, uniformWalk, rand);
			}
			if (origin.attemptSwap(target)) nAccepted++;
		}
		return nAccepted;
	}

	public int[] randomWalkDistTest(int nWalks, int hopsPerWalk, boolean uniform, Random rand) {
		int[] choiceFreq = new int[size()];
		int dupCount = 0;
		for (int i = 0; i < nWalks; i++) {
			SimpleNode origin = nodes.get(rand.nextInt(size()));
			SimpleNode dest = origin.randomWalk(hopsPerWalk, uniform, rand);
			choiceFreq[dest.index]++;
			if (origin == dest) dupCount++;
		}
		System.out.println("Origin selected as dest on " + dupCount + " walks out of " + nWalks);
		return choiceFreq;
	}

	/**
	 * Create some graphs; test them for statistical properties of interest.
	 */
	public static void main(String[] args) {
		int nNodes = 4000;

		int nWalks = 10 * 1000 * 1000;
		int nBuckets = 400;
		int hopsPerWalkUniform = 20;
		int hopsPerWalkCorrected = 40;

		int nTrials = 4;
		int[][][] pdfs = new int[nTrials][3][nBuckets];

		for (int trial = 0; trial < nTrials; trial++) {
			System.out.println("Creating test graph...");
			Random rand = new MersenneTwister(trial);
			Graph g = generate1dKleinbergGraph(new GraphParam(nNodes, 0.0, 0.0, true), rand, new PoissonDegreeSource(12));
			g.printGraphStats(true);
			int[] uniformWalkDist;
			int[] weightedWalkDist;
			int[] refDist = new int[nNodes];
			System.out.println("Computing reference distribution...");
			for (int i = 0; i < nWalks; i++) refDist[rand.nextInt(nNodes)]++;
			System.out.println("Computing uniform walks...");
			uniformWalkDist = g.randomWalkDistTest(nWalks, hopsPerWalkUniform, true, rand);
			System.out.println("Computing weighted walks...");
			weightedWalkDist = g.randomWalkDistTest(nWalks, hopsPerWalkCorrected, false, rand);

			Arrays.sort(refDist);
			Arrays.sort(uniformWalkDist);
			Arrays.sort(weightedWalkDist);
			int nodesPerBucket = nNodes / nBuckets;
			assert nBuckets * nodesPerBucket == nNodes;

			for (int i = 0; i < nNodes; i++) {
				pdfs[trial][0][i / nodesPerBucket] += refDist[i];
				pdfs[trial][1][i / nodesPerBucket] += uniformWalkDist[i];
				pdfs[trial][2][i / nodesPerBucket] += weightedWalkDist[i];
			}
		}

		System.out.println("Distribution PDFs:");
		for (int i = 0; i < nTrials; i++) {
			System.out.print("Reference\tUniform\tWeighted\t");
		}
		System.out.println();
		for (int i = 0; i < nBuckets; i++) {
			for (int trial = 0; trial < nTrials; trial++) {
				System.out.print(pdfs[trial][0][i] + "\t" + pdfs[trial][1][i] + "\t" + pdfs[trial][2][i] + "\t");
			}
			System.out.println();
		}
	}
}
