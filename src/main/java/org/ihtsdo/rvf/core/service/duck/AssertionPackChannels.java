package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The locations a pack may be fetched from, and the versions each one offers.
 *
 * <h2>What a channel is for</h2>
 *
 * <p>A pack is pinned by digest, because a report naming an unpinned pack cannot
 * say which assertions produced it. But a digest has to come from somewhere, and
 * a person copying hex out of release notes is a poor source: those notes carry
 * two different sha256 values - the pack's own identity and the asset's byte hash
 * - and pinning the wrong one fails a deployment with an error naming neither.
 * That is not hypothetical; it happened on 2026-09-16 and cost most of a day.
 *
 * <p>So a channel is a trusted base location that publishes an INDEX of what it
 * holds: each version, the URL to fetch, and the digest those bytes must have.
 * A caller then asks for {@code amtv4@2026.09.2} and the server resolves the
 * rest. The pin is not weakened - it is discovered instead of transcribed.
 *
 * <h2>What a channel is not</h2>
 *
 * <p>Not a trust root by itself. The index says what exists and what it hashes
 * to; the CHANNEL - this configuration, plus the credential that reads it - is
 * what says the index may be believed at all. An index fetched from an
 * unconfigured location is just JSON, and this class will not fetch one.
 *
 * <p>Not a way to change what a deployment serves, either. Resolving a version
 * produces a pin; nothing here swaps a corpus, writes state, or outlives the
 * request. What a run gets when it names no pack stays
 * {@code rvf.assertion.packs} - a values file, reviewed like any other change.
 * A channel only makes it possible to ASK for something else by name.
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 * rvf.assertion.channels[0] = name=amtv4;index=https://.../amtv4-index.json;packs=amtv4;authHeader=Bearer ${TOKEN}
 * </pre>
 *
 * <p>Same grammar as a pin - semicolons inside one entry, commas between - so
 * neither can drift into accepting what the other rejects. {@code packs} is the
 * pack names this channel may serve; a version resolved from it is refused if
 * the index offers a pack the channel was not configured to carry, which is what
 * stops a compromised or mistaken index from substituting a different corpus.
 */
public class AssertionPackChannels {

	private static final Logger LOGGER = LoggerFactory.getLogger(AssertionPackChannels.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One configured location, and the pack names it is allowed to serve. */
	public record Channel(String name, URI index, List<String> packs, String authHeader) {

		public Channel {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException(
						"a channel needs a name: it is what a caller asks for and what a "
								+ "report records");
			}
			if (index == null) {
				throw new IllegalArgumentException(
						"channel " + name + " has no index - without one the server cannot "
								+ "say which versions exist, and a caller would be back to "
								+ "supplying a digest by hand");
			}
			packs = packs == null || packs.isEmpty() ? List.of(name) : List.copyOf(packs);
		}

		/** Whether this channel is allowed to serve a pack of that name. */
		public boolean serves(String pack) {
			return packs.contains(pack);
		}
	}

	/** One version a channel offers. */
	public record Version(String version, URI uri, String sha256, int assertions,
			List<String> requires, String published, boolean draft) {
	}

	private final List<Channel> channels;
	private final AssertionPackFetcher fetcher;

	public AssertionPackChannels(List<Channel> channels, AssertionPackFetcher fetcher) {
		this.channels = channels == null ? List.of() : List.copyOf(channels);
		this.fetcher = fetcher;
	}

	public List<Channel> channels() {
		return channels;
	}

	public Optional<Channel> channel(String name) {
		return channels.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
	}

	/** The channel that may serve this pack name, if any is configured to. */
	public Optional<Channel> channelForPack(String pack) {
		return channels.stream().filter(c -> c.serves(pack)).findFirst();
	}

	/**
	 * The versions a channel offers, newest first.
	 *
	 * <p>Fetched every time rather than cached. An index is small, the answer
	 * changes whenever someone publishes, and a cached list that says a version
	 * does not exist when it does is a worse failure than an extra request.
	 */
	public List<Version> versions(Channel channel) throws IOException {
		byte[] body = fetcher.read(channel.index(), channel.authHeader());
		JsonNode index = MAPPER.readTree(body);
		String declared = index.path("channel").asText("");
		if (!declared.isBlank() && !declared.equalsIgnoreCase(channel.name())) {
			throw new IOException("channel " + channel.name() + " is configured to read "
					+ channel.index() + ", and that index declares itself to be '"
					+ declared + "'. Refusing it: an index naming a different channel is "
					+ "either misconfiguration or substitution, and both end with a "
					+ "release validated against assertions nobody chose.");
		}
		List<Version> out = new ArrayList<>();
		for (JsonNode entry : index.path("versions")) {
			String version = entry.path("version").asText("");
			String uri = entry.path("uri").asText("");
			String sha256 = entry.path("sha256").asText("");
			if (version.isBlank() || uri.isBlank() || sha256.isBlank()) {
				LOGGER.warn("channel {}: skipping an index entry missing version, uri or "
						+ "sha256 - a version that cannot be pinned cannot be offered",
						channel.name());
				continue;
			}
			List<String> requires = new ArrayList<>();
			for (JsonNode need : entry.path("requires")) {
				requires.add(need.path("pack").asText("") + " atLeast "
						+ need.path("atLeast").asText(""));
			}
			out.add(new Version(version, URI.create(uri), sha256,
					entry.path("assertions").asInt(0), requires,
					entry.path("published").asText(""), entry.path("draft").asBoolean(false)));
		}
		return out;
	}

	/**
	 * Turns {@code name@version} into a full pin.
	 *
	 * <p>The shorthand exists so a pipeline variable can be a version and
	 * nothing else. Anything more - a uri, a digest - is what a caller had to
	 * write before, and still may: a spec that already carries them is returned
	 * untouched, so a pin that names a location no channel serves keeps working
	 * for a one-off.
	 */
	public String resolve(String spec) throws IOException {
		String trimmed = spec == null ? "" : spec.trim();
		if (trimmed.isEmpty() || trimmed.contains(";") || trimmed.contains("=")) {
			return trimmed;
		}
		int at = trimmed.lastIndexOf('@');
		if (at <= 0 || at == trimmed.length() - 1) {
			throw new IOException("'" + trimmed + "' is not a pack spec and not "
					+ "name@version. A shorthand pin is exactly name@version, for example "
					+ "amtv4@2026.09.2.");
		}
		String pack = trimmed.substring(0, at);
		String wanted = trimmed.substring(at + 1);

		Channel channel = channelForPack(pack).orElseThrow(() -> new IOException(
				"no configured channel serves pack '" + pack + "'. Channels are the "
						+ "locations this deployment trusts, so an unlisted pack is "
						+ "refused rather than fetched: " + channels.stream()
						.map(c -> c.name() + "=" + c.packs()).toList()));

		List<Version> available = versions(channel);
		Version match = available.stream()
				.filter(v -> v.version().equals(wanted))
				.findFirst()
				.orElseThrow(() -> new IOException("channel " + channel.name()
						+ " offers no version '" + wanted + "'. It has: "
						+ available.stream().map(Version::version).toList()));

		LOGGER.info("resolved {}@{} from channel {} to {} ({})",
				pack, wanted, channel.name(), match.uri(), match.sha256());
		return "name=" + pack + ";version=" + match.version()
				+ ";uri=" + match.uri() + ";sha256=" + match.sha256()
				+ (channel.authHeader() == null || channel.authHeader().isBlank()
						? "" : ";authHeader=" + channel.authHeader());
	}

	/** Every spec resolved, in order, leaving full specs alone. */
	public List<String> resolveAll(List<String> specs) throws IOException {
		if (specs == null || specs.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>(specs.size());
		for (String spec : specs) {
			String resolved = resolve(spec);
			if (!resolved.isBlank()) {
				out.add(resolved);
			}
		}
		return out;
	}

	/** Channels parsed from configuration, in the pin grammar. */
	public static List<Channel> parse(List<String> specs) {
		List<Channel> out = new ArrayList<>();
		for (String spec : specs == null ? List.<String>of() : specs) {
			if (spec == null || spec.isBlank()) {
				continue;
			}
			Map<String, String> fields = new LinkedHashMap<>();
			for (String part : spec.split(";")) {
				String[] kv = part.split("=", 2);
				if (kv.length == 2) {
					fields.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
				}
			}
			String packs = fields.getOrDefault("packs", "");
			out.add(new Channel(
					fields.getOrDefault("name", ""),
					URI.create(fields.getOrDefault("index", "")),
					packs.isBlank() ? null : List.of(packs.split("\\s*\\|\\s*")),
					fields.get("authheader")));
		}
		return out;
	}
}
