package org.freenetproject.routing_simulator.graph.linklength;

import org.freenetproject.routing_simulator.graph.node.SimpleNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

/**
 * Generates links conforming to the 1/d Kleinberg distribution.
 */
public class KleinbergLinkSource extends LinkLengthSource {
	private final HashMap<SimpleNode, double[]> sumProbs;

	public KleinbergLinkSource(Random random, ArrayList<SimpleNode> nodes) {
		// No need for
		super(random, new ArrayList<SimpleNode>());
		sumProbs = new HashMap<SimpleNode, double[]>();
	}

	@Override
	public SimpleNode getPeer(final SimpleNode from) {
		/* Build probability array if it does not already exist.
		 *
		 * Find normalizing constant for this node - sum distance probabilities so that they
		 * they are in increasing order and may be searched through to find the closest link.
		 * Note that this means here the probability is proportional to 1/distance.
		 * sumProb is a non-normalized CDF of probabilities by node index.
		 */
		if (!sumProbs.containsKey(from)) {
			final double[] sumProb = new double[nodes.size()];
			double norm = 0.0;
			for (int j = 0; j < nodes.size(); j++) {
				if (j != from.index) {
					norm += 1.0 / from.distanceTo(nodes.get(j));
				}
				sumProb[j] = norm;
				//CDF must be non-decreasing
				if (j > 0) assert sumProb[j] >= sumProb[j-1];
			}
			sumProbs.put(from, sumProb);
		}
		assert sumProbs.containsKey(from);

		/*
		 * sumProb is a CDF, so to weight by it pick a "Y value" and find closest index.
		 * norm is now the highest (and last) value in the CDF, so this is picking
		 * a distance probability sum and finding the closest node for that distance.
		 * Because there are more nodes which match values in highly represented domains
		 * (steeper in the CDF) a random value is more likely to be in those areas.
		 */
		final double[] sumProb = sumProbs.get(from);
		final double norm = sumProb[sumProb.length - 1];
		double x = random.nextDouble() * norm;
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
		 */
		if (idx < 0) idx = -1 - idx;

		//idx is index of the first greater element, but use the lesser if it is closer.
		if (idx > 0 && Math.abs(x - sumProb[idx - 1]) < Math.abs(x - sumProb[idx])) idx--;

		//Assert that this actually is the closest.
		if (idx > 0) assert Math.abs(x - sumProb[idx]) < Math.abs(x - sumProb[idx - 1]);
		if (idx < sumProb.length - 1) assert Math.abs(x - sumProb[idx]) < Math.abs(x - sumProb[idx + 1]);

		return nodes.get(idx);
	}
}
