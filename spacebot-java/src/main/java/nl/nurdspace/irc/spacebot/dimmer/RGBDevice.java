package nl.nurdspace.irc.spacebot.dimmer;

import java.util.ArrayList;
import java.util.List;

public class RGBDevice extends DimmerDevice {
	private final int roodChannel;
	private final int groenChannel;
	private final int blauwChannel;
	
	public RGBDevice(int rood, int groen, int blauw) {
		this.roodChannel = rood;
		this.groenChannel = groen;
		this.blauwChannel = blauw;
	}
	
	public int getRed() {
		return this.roodChannel;
	}

	public int getGreen() {
		return this.groenChannel;
	}

	public int getBlue() {
		return this.blauwChannel;
	}

	public List<Integer> getChannels() {
		List<Integer> channels = new ArrayList<Integer>(3);
		channels.add(roodChannel);
		channels.add(groenChannel);
		channels.add(blauwChannel);
		return channels;
	}

	@Override
	public String toString() {
		return "RGB device (" + roodChannel + ", " + groenChannel + ", " + blauwChannel + ")";
	}
}
