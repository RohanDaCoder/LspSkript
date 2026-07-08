package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides signature help by showing the pattern of the syntax surrounding the
 * cursor, with the active {@code %type%} parameter highlighted.
 */
public class SignatureHelpProvider {

	private static final Pattern TYPE_PATTERN = Pattern.compile("%([^%]+)%");

	public SignatureHelp signatureHelp(String uri, String text, Position position) {
		String line = CompletionProvider.lineAt(text, position.getLine());
		String before = line.substring(0, Math.min(position.getCharacter(), line.length()));

		// Find the matching syntax by seeing if any registered pattern's prefix
		// appears in the text before the cursor.
		SyntaxRegistry reg = SyntaxRegistryAccess.registry();
		List<SyntaxInfo<?>> candidates = new ArrayList<>();
		addAll(reg.syntaxes(SyntaxRegistry.EFFECT), before, candidates);
		addAll(reg.syntaxes(SyntaxRegistry.CONDITION), before, candidates);
		addAll(reg.syntaxes(SyntaxRegistry.EXPRESSION), before, candidates);

		if (candidates.isEmpty())
			return null;

		SignatureInformation info = new SignatureInformation();
		// Use the first candidate's first pattern.
		String pattern = CompletionProvider.cleanPattern(candidates.get(0).patterns().iterator().next());
		info.setLabel(pattern);

		List<ParameterInformation> params = new ArrayList<>();
		Matcher m = TYPE_PATTERN.matcher(pattern);
		int active = 0;
		int idx = 0;
		int cursorTypesSeen = 0;
		while (m.find()) {
			String type = m.group(1).replaceAll("[-@0-9]", "").trim();
			ParameterInformation pi = new ParameterInformation();
			pi.setLabel(type);
			params.add(pi);
			// crude active-parameter detection: count %...% openings before cursor
			if (m.start() <= before.length())
				cursorTypesSeen++;
			idx++;
		}
		info.setParameters(params);

		SignatureHelp help = new SignatureHelp();
		help.setSignatures(Collections.singletonList(info));
		help.setActiveSignature(0);
		help.setActiveParameter(Math.max(0, cursorTypesSeen - 1));
		return help;
	}

	private static void addAll(Collection<? extends SyntaxInfo<?>> infos, String before, List<SyntaxInfo<?>> out) {
		String lowerBefore = before.toLowerCase(Locale.ENGLISH);
		for (SyntaxInfo<?> info : infos) {
			for (String pattern : info.patterns()) {
				String cleaned = CompletionProvider.cleanPattern(pattern).toLowerCase(Locale.ENGLISH);
				// The text before the cursor should overlap with the literal parts.
				if (!cleaned.isEmpty() && overlaps(lowerBefore, cleaned)) {
					out.add(info);
					break;
				}
			}
		}
	}

	private static boolean overlaps(String before, String pattern) {
		// Check that the last few words of `before` appear within the pattern.
		String[] words = before.trim().split("\\s+");
		if (words.length == 0)
			return false;
		int take = Math.min(3, words.length);
		StringBuilder tail = new StringBuilder();
		for (int i = words.length - take; i < words.length; i++) {
			if (i > words.length - take)
				tail.append(' ');
			tail.append(words[i]);
		}
		return pattern.contains(tail.toString().trim());
	}
}
