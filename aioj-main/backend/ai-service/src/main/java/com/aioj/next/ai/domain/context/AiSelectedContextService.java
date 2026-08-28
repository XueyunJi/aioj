package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiSelectedContextService {
    public boolean hasSelection(AiChatRequest request) {
        AiChatRequest.SelectionContext selection = request == null ? null : request.selectionContext();
        return selection != null && text(selection.selectedText()) != null;
    }

    public Map<String, Object> stateFocus(AiChatRequest.SelectionContext selection) {
        Map<String, Object> focus = new LinkedHashMap<>();
        if (selection == null) {
            return focus;
        }
        put(focus, "selectionId", selection.selectionId(), 80);
        put(focus, "sourceType", selection.sourceType(), 48);
        put(focus, "sourceMessageId", selection.sourceMessageId(), 80);
        put(focus, "sourceRole", selection.sourceRole(), 32);
        put(focus, "uiIntent", selection.uiIntent(), 48);
        put(focus, "selectedText", selection.selectedText(), 800);
        if (selection.selectionRange() != null) {
            focus.put("selectionRange", selection.selectionRange());
        }
        if (selection.codeContext() != null) {
            focus.put("codeContext", selection.codeContext());
        }
        if (selection.problemContext() != null) {
            focus.put("problemContext", selection.problemContext());
        }
        return focus;
    }

    public String contextPackBlock(AiChatRequest request) {
        AiChatRequest.SelectionContext selection = request == null ? null : request.selectionContext();
        if (selection == null || text(selection.selectedText()) == null) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("[Selected Context Focus]\n");
        append(block, "Source", selection.sourceType(), 120);
        append(block, "Source message id", selection.sourceMessageId(), 120);
        append(block, "Source role", selection.sourceRole(), 60);
        append(block, "UI intent", selection.uiIntent(), 80);
        if (selection.selectionRange() != null) {
            AiChatRequest.SelectionRange range = selection.selectionRange();
            String lineRange = range.startLine() == null && range.endLine() == null
                    ? ""
                    : "lines " + nvl(range.startLine()) + ".." + nvl(range.endLine());
            String offsetRange = range.startOffset() == null && range.endOffset() == null
                    ? ""
                    : "offsets " + nvl(range.startOffset()) + ".." + nvl(range.endOffset());
            append(block, "Range", (lineRange + " " + offsetRange).trim(), 120);
        }
        if (selection.codeContext() != null) {
            AiChatRequest.SelectedCodeContext code = selection.codeContext();
            append(block, "Code language", code.language(), 40);
            append(block, "Function", firstNonBlank(code.functionName(), code.enclosingSymbol()), 120);
            append(block, "Latest code message", code.latestCodeMessageId(), 120);
            append(block, "Code hash", code.codeHash(), 80);
        }
        if (selection.problemContext() != null) {
            AiChatRequest.SelectedProblemContext problem = selection.problemContext();
            append(block, "Problem", firstNonBlank(problem.title(), problem.problemId()), 180);
            if (problem.tags() != null && !problem.tags().isEmpty()) {
                append(block, "Problem tags", String.join(", ", problem.tags()), 220);
            }
            if (problem.constraints() != null && !problem.constraints().isEmpty()) {
                append(block, "Problem constraints", String.join("; ", problem.constraints()), 360);
            }
        }
        if (selection.surroundingContext() != null) {
            AiChatRequest.SurroundingContext surrounding = selection.surroundingContext();
            append(block, "Section", surrounding.sectionTitle(), 120);
            append(block, "Message preview", surrounding.messagePreview(), 260);
            append(block, "Before", surrounding.before(), 500);
            append(block, "After", surrounding.after(), 500);
        }
        String selectedMarkdown = text(selection.selectedMarkdown());
        if (selectedMarkdown != null) {
            block.append("- Selected markdown:\n")
                    .append(truncate(selectedMarkdown, 2000))
                    .append('\n');
        } else {
            block.append("- Selected text:\n")
                    .append(truncate(selection.selectedText(), 1600))
                    .append('\n');
        }
        block.append("- How to use it: Treat this selected span as the primary focus of the user's current question. ")
                .append("Do not answer with generic algorithm advice when the selection is code or a concrete explanation span.\n\n");
        return block.toString();
    }

    private void append(StringBuilder block, String label, String value, int max) {
        String text = text(value);
        if (text != null) {
            block.append("- ").append(label).append(": ").append(truncate(text, max)).append('\n');
        }
    }

    private void put(Map<String, Object> target, String key, String value, int max) {
        String text = text(value);
        if (text != null) {
            target.put(key, truncate(text, max));
        }
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String firstNonBlank(String first, String second) {
        String firstText = text(first);
        return firstText == null ? text(second) : firstText;
    }

    private String truncate(String value, int max) {
        String normalized = text(value);
        if (normalized == null) {
            return "";
        }
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    private String nvl(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }
}
