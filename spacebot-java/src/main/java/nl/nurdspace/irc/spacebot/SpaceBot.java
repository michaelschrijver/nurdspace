package nl.nurdspace.irc.spacebot;

import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.nurdspace.irc.spacebot.dimmer.Dimmer;
import nl.nurdspace.irc.spacebot.dimmer.DimmerDevice;
import nl.nurdspace.irc.spacebot.dimmer.RGBDevice;
import nl.nurdspace.irc.spacebot.dimmer.SimpleDevice;

import org.bff.javampd.MPD;
import org.bff.javampd.MPDPlayer;
import org.bff.javampd.exception.MPDConnectionException;
import org.bff.javampd.exception.MPDPlayerException;
import org.bff.javampd.exception.MPDResponseException;
import org.bff.javampd.objects.MPDSong;
import org.pircbotx.Channel;
import org.pircbotx.Colors;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.Listener;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.managers.ListenerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IRC bot to monitor space status and provide other services.
 * 
 * @author bjornl
 */
public class SpaceBot extends ListenerAdapter implements Listener,
		SpaceStatusChangeListener {
	/** Logger. */
	private static final Logger LOG = LoggerFactory.getLogger(SpaceBot.class);

	private static final DecimalFormat TEMP_FORMAT = new DecimalFormat("##0.0");
	private static final DecimalFormat SECONDS_FORMAT = new DecimalFormat("00");

	private static final String[] KUTMUZIEKBERICHTEN = new String[]{"Niemand luistert nog naar %s",
			"Van %s ga ik spontaan aan de spuitpoep",
			"%s is voor lutsers",
			"%s schaadt de gezondheid. Het kan longkanker en hartklachten veroorzaken.",
			"Kut.mu.ziek de; v 1. %s",
			"Volgens surrounder is %s nog erger dan Windows 3.1"};
	private final Channel channel;
	private final Dimmer dimmer;
	private final String mpdHost;
	private int dmxChannel;
	private int flashRepeats;
	private int flashTimeOff;
	private int flashTimeOn;
	private int lightsOffDelay;
	private List<Integer> dimmerChannels;
	private List<DimmerDevice> dimmerDevices;

	private static final Pattern REPLACE_COMMAND = Pattern
			.compile("(^s|[ #!]s)/[^/]+/[^/]+/?"); // ^s/.+/.+|[
													// |\\!|\\#]s/.+/.+

	private List<Event> events = new ArrayList<Event>(100);

	public SpaceBot(Channel channel, Dimmer dimmer, String mpdHost, int dimmerChannel, List<Integer> dimmerChannels, List<DimmerDevice> dimmerDevices) {
		this(channel, dimmer, mpdHost, true, dimmerChannel, dimmerChannels, dimmerDevices);
	}

	public SpaceBot(Channel channel, Dimmer dimmer, String mpdHost, boolean automatic,
			int dimmerChannel, List<Integer> dimmerChannels, List<DimmerDevice> dimmerDevices) {
		SpaceStatus.getInstance().setOpenAutomatically(automatic);
		this.channel = channel;
		this.dimmer = dimmer;
		this.dmxChannel = dimmerChannel;
		this.mpdHost = mpdHost;
		this.dimmerChannels = dimmerChannels;
		this.dimmerDevices = dimmerDevices;
	}

	public void setDimmerChannel(int dimmerChannel) {
		this.dmxChannel = dimmerChannel;
	}

	public void setLightsOffDelay(int lightsOffDelay) {
		this.lightsOffDelay = lightsOffDelay;
	}

	public void setFlashRepeats(int flashRepeats) {
		this.flashRepeats = flashRepeats;
	}

	public void setFlashTimeOn(int flashTimeOn) {
		this.flashTimeOn = flashTimeOn;
	}

	public void setFlashTimeOff(int flashTimeOff) {
		this.flashTimeOff = flashTimeOff;
	}

	/**
	 * Change the topic according to the given status.
	 * 
	 * @param open
	 *            true if the space is open, false if it is closed
	 */
	private void changeTopic(boolean open) {
		LOG.trace("changeTopic: open=" + open);
		String currentTopic = channel.getTopic();
		String cleanTopic;
		if (currentTopic.startsWith("Space is ")) {
			// Remove current message
			if (currentTopic.indexOf("|") > 0) {
				cleanTopic = currentTopic.substring(
						currentTopic.indexOf("|") + 1).trim();
			} else {
				cleanTopic = "";
			}
		} else {
			cleanTopic = currentTopic;
		}
		LOG.debug("changeTopic: cleanTopic=[" + cleanTopic + "]");
		String newTopic = getSpaceOpenMessage() + " | " + cleanTopic;
		LOG.debug("changeTopic: newTopic=[" + cleanTopic + "]");
		if (newTopic.equals(currentTopic)) {
			LOG.trace("changeTopic: current topic matches new topic, ignoring");
		} else {
			channel.getBot().setTopic(channel, newTopic);
			LOG.trace("changeTopic: changed topic");
		}
	}

	private String getSpaceOpenMessage() {
		LOG.trace("getSpaceOpenMessage: start");
		String spaceOpenMessage = "Space is "
				+ (SpaceStatus.getInstance().isOpen() ? "OPEN" : "CLOSED");
		LOG.trace("getSpaceOpenMessage: spaceOpenMessage=[" + spaceOpenMessage
				+ "]");
		return spaceOpenMessage;
	}

	/**
	 * Easy and recommended way to handle events: Override respective methods in
	 * {@link ListenerAdapter}.
	 * <p>
	 * This example shows how to work with the waitFor ability of PircBotX.
	 * Follow the inline comments for how this works
	 * <p>
	 * *WARNING:* This example requires using a Threaded listener manager (this
	 * is PircBotX's default)
	 * 
	 * @param event
	 *            A MessageEvent
	 * @throws Exception
	 *             If any Exceptions might be thrown, throw them up and let the
	 *             {@link ListenerManager} handle it. This can be removed though
	 *             if not needed
	 */
	public void onMessage(MessageEvent event) throws Exception {
		String message = event.getMessage();
		if (message.startsWith("!")) {
			// Command
			try {
				handleCommand(event);
			} catch (RuntimeException e) {
				LOG.error("Exception in command", e);
			}
		} else {
			if (isReplaceCommand(message)) {
				replace(event);
			} else {
				// Add the message to the store for future replacements
				if (events.size() >= 100) {
					events.remove(0);
				}
				events.add(event);
			}
		}
	}

	@Override
	public void onAction(ActionEvent event) throws Exception {
		if (events.size() >= 100) {
			events.remove(0);
		}
		events.add(event);
	}

	private void handleCommand(MessageEvent event) {
		String message = event.getMessage();
		String commandString = message.substring(1);
		String[] commandArray = commandString.split(" ");
		String command = commandArray[0];
		String[] parameters = new String[] {};
		if (commandArray.length > 1) {
			parameters = new String[commandArray.length - 1];
			System.arraycopy(commandArray, 1, parameters, 0, parameters.length);
		}
		if ("flash".equalsIgnoreCase(command)) {
			this.flash(event, parameters);
		} else if ("fade".equalsIgnoreCase(command)) {
			this.fade(event, parameters);
		} else if ("devices".equalsIgnoreCase(command)) {
			StringBuffer devices = new StringBuffer();
			for (int i = 0; i < this.dimmerDevices.size(); i++) {
				DimmerDevice device = dimmerDevices.get(i);
				devices.append(i).append(": [").append(device).append("] ");
			};
			event.getBot().sendMessage(event.getChannel(), devices.toString());
		} else if ("device".equalsIgnoreCase(command)) {
			int deviceNumber = Integer.parseInt(parameters[0].substring(1));
			DimmerDevice device = dimmerDevices.get(deviceNumber);
			if (device instanceof SimpleDevice) {
				StringBuffer buf = new StringBuffer("Device ").append(deviceNumber).append(": ");
				buf.append("simple device (channel ").append(device.getChannels().get(0)).append("=").append(dimmer.getCurrentLevel(device.getChannels().get(0))).append(")");
				event.getBot().sendMessage(event.getChannel(), buf.toString());
			} else if (device instanceof RGBDevice) {
				StringBuffer buf = new StringBuffer("Device ").append(deviceNumber).append(": ");
				buf.append("RGB device (redchan ").append(device.getChannels().get(0)).append("=");
				buf.append(dimmer.getCurrentLevel(device.getChannels().get(0))).append(", greenchan ").append(device.getChannels().get(1)).append("=");
				buf.append(dimmer.getCurrentLevel(device.getChannels().get(1))).append(", bluechan ").append(device.getChannels().get(2)).append("=");
				buf.append(dimmer.getCurrentLevel(device.getChannels().get(2))).append(")");
				event.getBot().sendMessage(event.getChannel(), buf.toString());
			} else {
				event.getBot().sendMessage(event.getChannel(), "onbekend type device");
			}
		} else if ("lights".equalsIgnoreCase(command)) {
			this.lights(event, parameters);
		} else if ("status".equalsIgnoreCase(command)) {
			this.status(event, parameters);
		} else if ("temp".equalsIgnoreCase(command)) {
			this.temp(event, parameters);
		} else if ("brul".equalsIgnoreCase(command)) {
			this.brul(event, parameters);
		} else if ("beledig".equalsIgnoreCase(command)) {
			this.beledig(event, parameters);
		} else if ("kutmuziek".equalsIgnoreCase(command)) {
			this.skipTrack(event, parameters, true);
		} else if ("next".equalsIgnoreCase(command)) {
			this.skipTrack(event, parameters, false);
		} else if ("volume".equalsIgnoreCase(command)) {
			this.volume(event, parameters);
		} else if ("louder".equalsIgnoreCase(command) || "harder".equalsIgnoreCase(command)) {
			this.harder(event, parameters);
		} else if ("quieter".equalsIgnoreCase(command) || "zachter".equalsIgnoreCase(command)) {
			this.zachter(event, parameters);
		} else if ("pause".equalsIgnoreCase(command)) {
			this.mpdPause(event, parameters);
		} else if ("play".equalsIgnoreCase(command)) {
			this.mpdPlay(event, parameters);
		} else if ("np".equalsIgnoreCase(command)) {
			this.showSong(event, parameters);
		} else if ("element".equalsIgnoreCase(command)) {
			this.showElement(event, parameters);
		} else if ("lock".equalsIgnoreCase(command)) {
			this.showLocks(event, parameters);
		} else if ("open".equalsIgnoreCase(command) || "state".equalsIgnoreCase(command)) {
			this.showOpen(event, parameters);
		} else if ("fixtopic".equalsIgnoreCase(command)) {
			if (channel.isOp(event.getBot().getUserBot())) {
				this.changeTopic(SpaceStatus.getInstance().isOpen());
			} else {
				event.respond("Can't, I'm not an op! /op me!");
			}
		}
	}

	private void showLocks(MessageEvent event, String[] parameters) {
		event.getBot().sendMessage(event.getChannel(), "Front door is " + (SpaceStatus.getInstance().isFrontDoorLocked() == null ? "unknown" : (SpaceStatus.getInstance().isFrontDoorLocked() ? "locked" : "unlocked")));
		event.getBot().sendMessage(event.getChannel(), "Back door is " + (SpaceStatus.getInstance().isBackDoorLocked() == null ? "unknown" : (SpaceStatus.getInstance().isBackDoorLocked() ? "locked" : "unlocked")));
	}
	
	private void showElement(MessageEvent event, String[] parameters) {
		if (parameters.length > 0) {
			Element element;
			
			// Try abbreviation
			element = Elements.getInstance().getElementByAbbreviation(parameters[0]);
			if (element == null) {
				// Try number
				try {
					int number = Integer.parseInt(parameters[0]);
					element = Elements.getInstance().getElementByNumber(number);
				} catch (NumberFormatException e) {
					// Not important
				}
			}
			if (element == null) {
				// Try name
				element = Elements.getInstance().getElementByName(parameters[0]);
				// TODO scan through list and check part of name
			}
			if (element == null) {
				event.getBot().sendMessage(event.getChannel(), "element '" + parameters[0] + "' is unknown");
			} else {
				event.getBot().sendMessage(event.getChannel(), element.getNumber() + ". " + element.getName() + " (" + element.getAbbreviation() + "); atomic weight: " + element.getWeight());
			}
		}
	}
	
	private void showOpen(MessageEvent event, String[] parameters) {
		event.getBot().sendMessage(event.getChannel(), getSpaceOpenMessage());
	}
	
	private void flash(MessageEvent event, String[] parameters) {
		if (SpaceStatus.getInstance().isOpen()) {
			dimmer.flash(dimmerDevices, this.flashRepeats,
					this.flashTimeOn, this.flashTimeOff);
		} else {
			event.respond("nope, space is closed");
		}
	}

	private void fade(MessageEvent event, String[] parameters) {
		if (SpaceStatus.getInstance().isOpen()) {
			// Drie formaten: fade <level>; fade <level> <kanaal>; fade <level> #<device>
			// Later nog: fade <r,g,b> #<device>
			switch (parameters.length) {
			case 0:
				event.respond("give me a level or RGB value (and optionally a channel/device)");
				break;
			case 1:
				if (parameters[0].startsWith("#")) {
					// RGB kan niet op default channel
					event.respond("give me a device if you want to use rgb!");
				} else {
					dimmer.fade(dmxChannel, Integer.parseInt(parameters[0]));
				}
				break;
			case 2:
				if (parameters[0].startsWith("#")) {
					if (parameters[1].startsWith("#")) {
						// RGB op device
						// Check of device rgb is
						int deviceNumber = Integer.parseInt(parameters[1].substring(1).trim());
						if (dimmerDevices.get(deviceNumber) instanceof RGBDevice) {
							RGBDevice device = (RGBDevice) dimmerDevices.get(deviceNumber);
							if (parameters[0].length() != 7) {
								event.respond("give me an RGB value in hex (rrggbb), like so: #FF0080");
							} else {
								String red = parameters[0].substring(1, 3);
								String green = parameters[0].substring(3, 5);
								String blue = parameters[0].substring(5);
								dimmer.fadeAbsolute(device.getRed(), Integer.parseInt(red, 16));
								dimmer.fadeAbsolute(device.getGreen(), Integer.parseInt(green, 16));
								dimmer.fadeAbsolute(device.getBlue(), Integer.parseInt(blue, 16));
							}
						} else {
							event.respond("give me an RGB device if you want to use rgb!");
						}
					} else {
						event.respond("give me a device, not a channel, if you want to use rgb!");
					}
				} else {
					// "Gewoon" level
					int level = Integer.parseInt(parameters[0]);
					if ("all".equals(parameters[1])) {
						for (int channel : dimmerChannels) {
							dimmer.fade(channel, level);
						}
					} else if (parameters[1].startsWith("#")) {
						int deviceNumber = Integer.parseInt(parameters[1].substring(1).trim());
						DimmerDevice device = dimmerDevices.get(deviceNumber);
						for (int channel : device.getChannels()) {
							dimmer.fade(channel, level);
						}
					} else {
						// Ouderwets: level op channel
						dimmer.fade(Integer.parseInt(parameters[1]), level);
					}
				}
			}
		} else {
			event.respond("nope, space is closed");
		}
	}

	private void lights(MessageEvent event, String[] parameters) {
		Integer fluorescents = SpaceStatus.getInstance().getFluorescentLighting();
		String fluorescentsMessage = "unknown";
		if (fluorescents != null) {
			Boolean fluorescentsOnOff = SpaceStatus.getInstance().isFluorescentLightOn();
			fluorescentsMessage = fluorescents + "/1023";
			if (fluorescentsOnOff != null) {
				fluorescentsMessage += " (" + (fluorescentsOnOff ? "ON" : "OFF") + ")";
			}
		}
		event.getBot()
				.sendMessage(
						event.getChannel(),
						"Fluorescent lighting "
								+ fluorescentsMessage);
		StringBuilder dimmedLights = new StringBuilder("Dimmer devices: ");
		int deviceNr = 0;
		for (DimmerDevice device : dimmerDevices) {
			dimmedLights.append(deviceNr++).append("=");
			if (device instanceof RGBDevice) {
				RGBDevice rgbDevice = (RGBDevice) device;
				dimmedLights.append("[").append(dimmer.getCurrentLevel((rgbDevice).getRed()));
				dimmedLights.append(",").append(dimmer.getCurrentLevel((rgbDevice).getGreen()));
				dimmedLights.append(",").append(dimmer.getCurrentLevel((rgbDevice).getBlue())).append("]");
			} else {
				dimmedLights.append(dimmer.getCurrentLevel(device.getChannels().iterator().next()));
			}
			if (deviceNr < dimmerDevices.size()) {
				dimmedLights.append(",");
			}
		}
		event.getBot().sendMessage(event.getChannel(), dimmedLights.toString());
	}

	private void temp(MessageEvent event, String[] parameters) {
		event.getBot().sendMessage(
				event.getChannel(),
				"Space temperature is "
						+ TEMP_FORMAT.format(SpaceStatus.getInstance()
								.getTemperature()) + " degrees Celsius");
	}

	private void status(MessageEvent event, String[] parameters) {
		Boolean fluorescents = SpaceStatus.getInstance().isFluorescentLightOn();
		String fluorescentsMessage = "fluorescent lights: ";
		if (fluorescents == null) {
			fluorescentsMessage += "unknown";
		} else {
			fluorescentsMessage += fluorescents ? "ON" : "OFF";
		}
		Float temperature = SpaceStatus.getInstance().getTemperature();
		String tempMessage = "unknown";
		if (temperature != null) {
			tempMessage = TEMP_FORMAT.format(temperature) + " C";
		}
		event.getBot().sendMessage(
				event.getChannel(),
				getSpaceOpenMessage() + "; " + fluorescentsMessage + "; " + tempMessage);
	}

	private void beledig(MessageEvent event, String[] parameters) {
		// TODO implement
	}

	private void brul(MessageEvent event, String[] parameters) {
		String brul = Colors.BOLD + combine(" ", parameters).toUpperCase()
				+ "!" + Colors.NORMAL;
		event.getBot().sendMessage(event.getChannel(), brul);
	}

	private void skipTrack(MessageEvent event, String[] parameters, boolean kutmuziek) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			int random = 0;
			StringBuilder songInfoString = null;
			if (kutmuziek) {
				MPDSong song = mpdPlayer.getCurrentSong();
				songInfoString = new StringBuilder();
				if (song.getArtist() == null) {
					songInfoString.append(song.getTitle()).append(" van ")
							.append(song.getFile());
				} else {
					songInfoString.append(song.getTitle()).append(" van ")
							.append(song.getArtist());
				}
				
				random = new Random(System.currentTimeMillis()).nextInt(KUTMUZIEKBERICHTEN.length);
			}
			mpdPlayer.playNext();
			mpd.close();
			if (kutmuziek) {
				LOG.debug("kutmuziek random = " + random);
				String message = String.format(KUTMUZIEKBERICHTEN[random], songInfoString.toString());
				event.getBot().sendMessage(event.getChannel(), message);
			}
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't skip the song");
			LOG.error("skipTrack: Error skipping", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("skipTrack: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("skipTrack: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("skipTrack: Error connecting", e);
		}
	}

	private void zachter(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			int hoeveel = 10;
			if (parameters.length == 1) {
				hoeveel = Integer.parseInt(parameters[0]);
			}
			int huidig = mpdPlayer.getVolume();
			int nieuw = huidig - hoeveel;
			if (nieuw < 0) {
				nieuw = 0;
			}
			mpdPlayer.setVolume(nieuw);
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't lower volume");
			LOG.error("zachter: Error skipping", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("zachter: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("zachter: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("zachter: Error connecting", e);
		}
	}

	private void harder(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			int hoeveel = 10;
			if (parameters.length == 1) {
				hoeveel = Integer.parseInt(parameters[0]);
			}
			int huidig = mpdPlayer.getVolume();
			int nieuw = huidig + hoeveel;
			if (nieuw > 100) {
				nieuw = 100;
			}
			mpdPlayer.setVolume(nieuw);
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't skip the song");
			LOG.error("harder: Error lowering volume", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("harder: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("harder: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("harder: Error connecting", e);
		}
	}

	private void volume(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			if (parameters.length == 1) {
				int volume = Integer.parseInt(parameters[0]);
				if (volume > 100) {
					volume = 100;
				} else if (volume < 0) {
					volume = 0;
				}
				mpdPlayer.setVolume(volume);
			} else {
				int volume = mpdPlayer.getVolume();
				event.getBot().sendMessage(event.getChannel(), "volume: " + volume);
			}
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't read or change volume");
			LOG.error("volume: Error setting or reading volume", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("volume: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("volume: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("volume: Error connecting", e);
		}
	}

	private void mpdPause(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			mpdPlayer.pause();
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't pause");
			LOG.error("mpdPause: Error skipping", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("mpdPause: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("mpdPause: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("mpdPause: Error connecting", e);
		}
	}

	private void mpdPlay(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			mpdPlayer.play();
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't play");
			LOG.error("mpdPlay: Error playing", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("mpdPlay: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("mpdPlay: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("mpdPlay: Error connecting", e);
		}
	}

	private void showSong(MessageEvent event, String[] parameters) {
		try {
			MPD mpd = new MPD(this.mpdHost, 6600);
			MPDPlayer mpdPlayer = mpd.getMPDPlayer();
			MPDSong song = mpdPlayer.getCurrentSong();
			StringBuilder songInfo = new StringBuilder();
			if (song.getArtist() == null) {
				songInfo.append(song.getFile()).append(" - ")
						.append(song.getTitle());
			} else {
				songInfo.append(song.getArtist()).append(" - ")
						.append(song.getTitle());
				int time = song.getLength();
				songInfo.append(" [").append(time / 60).append(":");
				songInfo.append(SECONDS_FORMAT.format(time % 60))
						.append("]");
			}
			event.getBot().sendMessage(event.getChannel(), "np: " + songInfo.toString());
			mpd.close();
		} catch (MPDPlayerException e) {
			event.respond("sorry, couldn't show the song");
			LOG.error("showSong: Error reading current song", e);
		} catch (MPDConnectionException e) {
			event.respond("sorry, couldn't connect to MPD");
			LOG.error("showSong: Error connecting", e);
		} catch (MPDResponseException e) {
			LOG.error("showSong: Error connecting", e);
		} catch (UnknownHostException e) {
			event.respond("sorry, couldn't find the MPD host");
			LOG.error("showSong: Error connecting", e);
		}
	}
	
	private String combine(String glue, String... values) {
		int length = values.length;
		if (length == 0) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		out.append(values[0]);
		for (int x = 1; x < length; ++x)
			out.append(glue).append(values[x]);
		return out.toString();
	}

	@Override
	public void spaceStatusChanged(int eventType, Object status) {
		LOG.trace("spaceStatusChanged: " + eventType);
		switch (eventType) {
		case SpaceStatusChangeListener.EVENT_SPACE_OPEN_CLOSE:
			LOG.trace("spaceStatusChanged: open=" + status);
			if (SpaceStatus.getInstance().isOpenAutomatically()) {
				this.changeTopic((Boolean) status);
				if ((Boolean) status) {
					LOG.info("spaceStatusChanged: opened");
					LOG.info("spaceStatusChanged: start mpd");
					try {
						MPD mpd = new MPD(this.mpdHost, 6600);
						MPDPlayer mpdPlayer = mpd.getMPDPlayer();
						mpdPlayer.play();
						mpd.close();
					} catch (MPDPlayerException e) {
						LOG.error("spaceStatusChanged: Error starting music", e);
					} catch (MPDConnectionException e) {
						LOG.error("spaceStatusChanged: Error starting music", e);
					} catch (MPDResponseException e) {
						LOG.error("spaceStatusChanged: Error starting music", e);
					} catch (UnknownHostException e) {
						LOG.error("spaceStatusChanged: Error starting music", e);
					}
					LOG.info("spaceStatusChanged: lights on");
					new Thread() {
						@Override
						public void run() {
							dimmer.fadeIn(dmxChannel);
						}
					}.start();
					
					LOG.info("spaceStatusChanged: opened, fading in started in separate thread");
				} else {
					LOG.info("spaceStatusChanged: closed");
					LOG.info("spaceStatusChanged: stop mpd");
					try {
						MPD mpd = new MPD(this.mpdHost, 6600);
						MPDPlayer mpdPlayer = mpd.getMPDPlayer();
						mpdPlayer.stop();
						mpd.close();
					} catch (MPDPlayerException e) {
						LOG.error("spaceStatusChanged: Error stopping music", e);
					} catch (MPDConnectionException e) {
						LOG.error("spaceStatusChanged: Error stopping music", e);
					} catch (MPDResponseException e) {
						LOG.error("spaceStatusChanged: Error stopping music", e);
					} catch (UnknownHostException e) {
						LOG.error("spaceStatusChanged: Error stopping music", e);
					}
					LOG.info("spaceStatusChanged: lights off");
					new Thread() {
						@Override
						public void run() {
							int i = 0;
							for (int channel : dimmerChannels) {
								dimmer.fadeOut(channel, lightsOffDelay + 3 * i++);
							}
						}
					}.start();
					LOG.info("spaceStatusChanged: fading out started in separate thread");
				}
			}
			break;
		case SpaceStatusChangeListener.EVENT_BACK_DOOR_LOCK:
			LOG.trace("spaceStatusChanged: backDoorLocked=" + status);
			this.channel.getBot().sendMessage(this.channel, "back door was " + (((Boolean) status).booleanValue() ? "locked" : "unlocked"));
			break;
		case SpaceStatusChangeListener.EVENT_FRONT_DOOR_LOCK:
			LOG.trace("spaceStatusChanged: frontDoorLocked=" + status);
			this.channel.getBot().sendMessage(this.channel, "front door was " + (((Boolean) status).booleanValue() ? "locked" : "unlocked"));
			break;
		}
	}

	private void replace(final MessageEvent event) {
		if (isReplaceCommand(event.getMessage())) {
			LOG.debug("process: message contains a replace command");
			String replaceCommand = getReplaceCommand(event.getMessage());
			LOG.debug("process: replace command: " + replaceCommand);
			String[] commandElements = replaceCommand.split("/");
			String snippet = commandElements[1];
			String replacement = commandElements[2];
			String origin;

			List<Event> reversedMessages = new ArrayList<Event>(100);
			reversedMessages.addAll(events);
			Collections.reverse(reversedMessages);
			String messageText;
			for (Event aMessage : reversedMessages) {

				if (aMessage instanceof MessageEvent) {
					messageText = ((MessageEvent) aMessage).getMessage();
				} else if (aMessage instanceof ActionEvent) {
					messageText = ((ActionEvent) aMessage).getAction();
				} else {
					LOG.error("process: unsupported message type in store: "
							+ aMessage.getClass().getName());

					throw new IllegalStateException(
							"Unsupported message type in store");

				}

				if (messageText.contains(snippet)) {

					String replaced = messageText.replace(snippet, replacement);
					if (aMessage instanceof MessageEvent) {
						MessageEvent message = (MessageEvent) aMessage;
						aMessage.getBot().sendMessage(
								message.getChannel(),
								"<" + message.getUser().getNick() + "> " + replaced);
						MessageEvent replacedMessage = new MessageEvent(message.getBot(), message.getChannel(), message.getUser(), replaced);
						if (events.size() >= 100) {
							events.remove(0);
						}
						events.add(replacedMessage);
					} else if (aMessage instanceof ActionEvent) {
						ActionEvent action = (ActionEvent) aMessage;
						aMessage.getBot().sendMessage(
								action.getChannel(), 
								"* " + action.getUser().getNick() + " "
								+ replaced);
						ActionEvent replacedAction = new ActionEvent(action.getBot(), action.getUser(), action.getChannel(), replaced);
						if (events.size() >= 100) {
							events.remove(0);
						}
						events.add(replacedAction);
					}

					else {

						// This cannot happen

						LOG.error("process: unsupported message type in store: "
								+ aMessage.getClass().getName());

						throw new IllegalStateException(
								"Unsupported message type in store");

					}

					break;

				}

			}

		}

		else {

			LOG.debug("process: message is not a replace command");
			//
			// if (messageToProcess instanceof PublicMessage
			// || messageToProcess instanceof Action) {
			//
			// LOG.debug("process: message is added to the store");
			//
			// messages.addMessage(messageToProcess);
			//
			// }
			//
			// return false;

		}

	}

	/**
	 * 
	 * Detect whether the given message contains a replace command.
	 * 
	 * 
	 @param message
	 *            the message
	 * 
	 * 
	 @return true if the message contains a replace command
	 */

	private boolean isReplaceCommand(final String message) {

		return REPLACE_COMMAND.matcher(message).find();

	}

	/**
	 * 
	 * Retrieves the replace command from the given message.
	 * 
	 * 
	 @param message
	 *            the message
	 * 
	 * 
	 @return true if the message contains a replace command
	 */

	private String getReplaceCommand(final String message) {

		Matcher matcher = REPLACE_COMMAND.matcher(message);

		if (!matcher.find()) {

			throw new IllegalArgumentException(
					"The message does not contain a replace command");

		}

		return matcher.group();

	}
}