package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * L4 heuristic detector for "complete submittable source code" (design doc §5.4,
 * P3-5). Frozen decisions: no official-solution similarity, no AST — five
 * feature families are matched with language-aware patterns (C/C++/Java/Python):
 *
 * <ol>
 *   <li>{@code ENTRY_POINT} — main entry ({@code int main(}, {@code public static
 *       void main}, {@code if __name__ == '__main__'}, {@code def main(}).</li>
 *   <li>{@code INPUT_READING} — stdin reading ({@code cin >>}, {@code scanf(},
 *       {@code input()}, {@code BufferedReader}/{@code Scanner}, {@code sys.stdin}).</li>
 *   <li>{@code CORE_LOGIC} — algorithmic structure: at least two loop/conditional
 *       constructs ({@code for}, {@code while}, {@code if}, {@code switch}).</li>
 *   <li>{@code OUTPUT_WRITING} — answer output ({@code cout <<}, {@code printf(},
 *       {@code print(}, {@code System.out.print}).</li>
 *   <li>{@code COMPILABILITY} — compiles-as-is traits: {@code #include}, import
 *       lines, {@code public class}, a fenced code block carrying a language tag,
 *       or dense semicolon+brace structure.</li>
 * </ol>
 *
 * <p>A draft hitting at least {@code aioj.ai.agent-core.output-guard.full-code
 * .min-features} families (default 4, clamped 1..5) is judged complete code.
 * Idea-level fragments (a loop plus one print) hit at most two families and
 * stay allowed. The hit family names are returned for audit detail; the draft
 * text itself is never retained here.</p>
 */
@Service
public class FullCodeHeuristicDetector {

    public enum Feature {
        ENTRY_POINT,
        INPUT_READING,
        CORE_LOGIC,
        OUTPUT_WRITING,
        COMPILABILITY
    }

    /** Detection outcome: the judged flag plus the hit feature families (audit vocabulary). */
    public record Detection(boolean fullCode, List<Feature> features) {
    }

    private static final Pattern ENTRY_POINT = Pattern.compile(
            "int\\s+main\\s*\\(|void\\s+main\\s*\\(|public\\s+static\\s+void\\s+main\\s*\\("
                    + "|if\\s+__name__\\s*==\\s*['\"]__main__['\"]|def\\s+main\\s*\\(");
    private static final Pattern INPUT_READING = Pattern.compile(
            "cin\\s*>>|scanf\\s*\\(|fgets\\s*\\(|getline\\s*\\(|BufferedReader|new\\s+Scanner\\s*\\("
                    + "|sys\\.stdin|readLine\\s*\\(|\\binput\\s*\\(");
    private static final Pattern CORE_LOGIC = Pattern.compile(
            "for\\s*\\(|while\\s*\\(|if\\s*\\(|switch\\s*\\("
                    + "|for\\s+\\w+\\s+in\\s+|while\\s+\\w|elif\\s");
    private static final Pattern OUTPUT_WRITING = Pattern.compile(
            "cout\\s*<<|printf\\s*\\(|puts\\s*\\(|print\\s*\\(|System\\.out\\.print|writeln\\s*\\(");
    private static final Pattern INCLUDE_OR_IMPORT = Pattern.compile(
            "#\\s*include|^\\s*import\\s+[\\w.]+|^\\s*from\\s+\\w+\\s+import\\s|public\\s+class\\s",
            Pattern.MULTILINE);
    private static final Pattern FENCED_CODE_WITH_LANGUAGE = Pattern.compile(
            "```\\s*(?:cpp|c\\+\\+|c|java|python|py)\\b", Pattern.CASE_INSENSITIVE);

    private final int minFeatures;

    @Autowired
    public FullCodeHeuristicDetector(AiProperties properties) {
        this(properties.getAgentCore().getOutputGuard().getFullCode().getMinFeatures());
    }

    FullCodeHeuristicDetector(int minFeatures) {
        this.minFeatures = Math.min(5, Math.max(1, minFeatures));
    }

    public Detection detect(String text) {
        if (text == null || text.isBlank()) {
            return new Detection(false, List.of());
        }
        List<Feature> features = new ArrayList<>();
        if (ENTRY_POINT.matcher(text).find()) {
            features.add(Feature.ENTRY_POINT);
        }
        if (INPUT_READING.matcher(text).find()) {
            features.add(Feature.INPUT_READING);
        }
        if (countMatches(CORE_LOGIC, text) >= 2) {
            features.add(Feature.CORE_LOGIC);
        }
        if (OUTPUT_WRITING.matcher(text).find()) {
            features.add(Feature.OUTPUT_WRITING);
        }
        if (hasCompilabilityTrait(text)) {
            features.add(Feature.COMPILABILITY);
        }
        return new Detection(features.size() >= minFeatures, List.copyOf(features));
    }

    private boolean hasCompilabilityTrait(String text) {
        if (INCLUDE_OR_IMPORT.matcher(text).find() || FENCED_CODE_WITH_LANGUAGE.matcher(text).find()) {
            return true;
        }
        // Dense statement terminators plus a block structure: characteristic of
        // brace-language complete programs even without visible includes/imports.
        return countSemicolons(text) >= 3 && text.contains("{") && text.contains("}");
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countSemicolons(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ';') {
                count++;
            }
        }
        return count;
    }
}
