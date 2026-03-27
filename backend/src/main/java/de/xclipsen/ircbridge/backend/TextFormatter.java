package de.xclipsen.ircbridge.backend;

public final class TextFormatter {
	private TextFormatter() {
	}

	public static String apply(String template, String... replacements) {
		String result = template;

		for (int index = 0; index + 1 < replacements.length; index += 2) {
			result = result.replace(replacements[index], replacements[index + 1]);
		}

		return result;
	}
}
