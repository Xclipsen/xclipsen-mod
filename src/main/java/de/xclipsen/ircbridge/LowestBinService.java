package de.xclipsen.ircbridge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public final class LowestBinService {
	private static final Gson GSON = new Gson();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Double>>() { }.getType();
	private static final long REFRESH_INTERVAL_MS = 120_000L;
	private static final String LOWEST_BIN_URL = "https://moulberry.codes/lowestbin.json.gz";
	private static final int MAX_ENTRY_COUNT = 250_000;

	private final Logger logger;
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "xclipsen-lowest-bin-fetch");
		thread.setDaemon(true);
		return thread;
	});
	private volatile Map<String, Long> lowestBins = Map.of();
	private volatile long lastRefreshAt;
	private volatile boolean refreshInFlight;

	public LowestBinService(Logger logger) {
		this.logger = logger;
	}

	public void refreshIfNeeded() {
		if (refreshInFlight || System.currentTimeMillis() - lastRefreshAt < REFRESH_INTERVAL_MS) {
			return;
		}

		refreshInFlight = true;
		executor.execute(this::refreshNow);
	}

	public long getLowestBin(String internalName) {
		if (internalName == null || internalName.isBlank()) {
			return -1L;
		}

		refreshIfNeeded();
		return lowestBins.getOrDefault(internalName, -1L);
	}

	public void stop() {
		executor.shutdownNow();
	}

	private void refreshNow() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(LOWEST_BIN_URL))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
			HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() != 200) {
				logger.warn("Lowest BIN refresh returned HTTP {}", response.statusCode());
				return;
			}

			Map<String, Long> parsed = parseResponse(response.body());
			if (!parsed.isEmpty()) {
				lowestBins = parsed;
				lastRefreshAt = System.currentTimeMillis();
				logger.info("Loaded {} lowest-bin entries from {}", parsed.size(), LOWEST_BIN_URL);
			}
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return;
			}

			logger.debug("Failed to refresh lowest-bin prices", exception);
		} finally {
			refreshInFlight = false;
		}
	}

	private static Map<String, Long> parseResponse(byte[] body) throws IOException {
		try (GZIPInputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(body));
			 Reader reader = new InputStreamReader(gzipStream, StandardCharsets.UTF_8)) {
			Map<String, Double> raw = GSON.fromJson(reader, MAP_TYPE);
			Map<String, Long> converted = new ConcurrentHashMap<>();

			if (raw != null) {
				for (Map.Entry<String, Double> entry : raw.entrySet()) {
					if (converted.size() >= MAX_ENTRY_COUNT) {
						break;
					}

					if (entry.getValue() == null) {
						continue;
					}

					converted.put(entry.getKey(), entry.getValue().longValue());
				}
			}

			return converted;
		}
	}
}
