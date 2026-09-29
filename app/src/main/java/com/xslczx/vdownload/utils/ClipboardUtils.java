package com.xslczx.vdownload.utils;

public final class ClipboardUtils {

    // 分享文案里常见的「/2024/ 复制此链接」类尾部杂质，泛化成年份段
    // 避免硬编码 2023~2026 在 2027 年后失效；取最早出现的一段截断
    private static final java.util.regex.Pattern YEAR_SEGMENT =
            java.util.regex.Pattern.compile("[/ ](19|20)\\d{2}/");

    private ClipboardUtils() {
        throw new AssertionError("No instances");
    }

    // 从文本中提取链接部分，去除中文、标点和特定年份路径
    public static String extractCleanUrl(String text) {
        if (text == null || text.isBlank() || !text.contains("http")) {
            return null;
        }

        String url = text.substring(text.indexOf("http"));

        int end = 0;
        for (; end < url.length(); end++) {
            char currentChar = url.charAt(end);
            if (currentChar >= '一' && currentChar <= '龥') {
                break;
            }
        }
        if (end > 0) {
            url = url.substring(0, end);
        }

        url = url.replace("，", "");

        java.util.regex.Matcher yearMatcher = YEAR_SEGMENT.matcher(url);
        if (yearMatcher.find() && yearMatcher.start() > 0) {
            url = url.substring(0, yearMatcher.start());
        }

        int spaceIndex = url.indexOf(" ");
        if (spaceIndex > 0 && spaceIndex < url.length()) {
            url = url.substring(0, spaceIndex);
        }

        url = url.trim();
        return url.isEmpty() ? null : url;
    }
}
