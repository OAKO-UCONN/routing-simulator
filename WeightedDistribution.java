import java.io.*;
import java.util.ArrayList;
import java.util.Random;

/**
 * Selects from a weighted distribution.
 * TODO: This is structured in a way which involves duplicate calculation because the events are constructed as the file
 * is read. This means dividing by the total size each time.
 */
public class WeightedDistribution {

	private class Event {
		public final int value, occurrences;
		public Event(int value, int occurrences) {
			this.value = value;
			this.occurrences = occurrences;
		}
	}

	private final ArrayList<Event> events;
	private final int totalOccurances;
	private final Random random;

	/**
	 * Replicates given distribution of values.
	 * TODO: Does Java have templating? Limiting to returning integers.
	 * @param filename file to read from. Format "[number] [number of occurrences]"
	 * @param random Used for random values.
	 */
	public WeightedDistribution(String filename, Random random) {
		this.events = new ArrayList<Event>();
		this.random = random;
		int tentativeTotal = 0;
		try {
			BufferedReader reader = new BufferedReader(new FileReader(new File(filename)));

			String line;
			//TODO: This seems like a C++ way of doing things. What's the Java way?
			while ( (line = reader.readLine()) != null) {
				String[] tokens = line.split(" ");
				assert tokens.length == 2;
				events.add(new Event(Integer.valueOf(tokens[0]), Integer.valueOf(tokens[1])));
			}

			for (Event event : events) tentativeTotal += event.occurrences;
		} catch (FileNotFoundException e) {
			System.out.println("Unable to open file \"" + filename + "\".");
			//TODO: Is there better behavior?
			System.exit(1);
		} catch (IOException e) {
			//TODO: Should these be thrown upwards or what?
			System.exit(2);
		}
		this.totalOccurances = tentativeTotal;
	}

	/**
	 * @return Random value selected with probability proportional to its occurrences relative the total number of
	 * occurrences.
	 */
	public int randomValue() {
		//totalOccurances is sum of all occurrences, and the random number can be up to but not including it,
		int sum = 0, rand = random.nextInt(totalOccurances), i = 0;
		for (; rand > sum; i++) sum += events.get(i).occurrences;
		//i could have been incremented past end of array if the loop executed.
		return events.get(i == 0 ? 0 : i - 1).value;
	}
}
