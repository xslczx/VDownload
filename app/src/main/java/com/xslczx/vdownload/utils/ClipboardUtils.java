package com.xslczx.vdownload.utils;

public final class ClipboardUtils {

    private static final String[] YEAR_SEGMENTS = {"2023/", "2024/", "2025/", "2026/"};

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

        for (String yearSegment : YEAR_SEGMENTS) {
            int yearIndex = url.indexOf(yearSegment);
            if (yearIndex > 0) {
                url = url.substring(0, yearIndex);
            }
        }

        int spaceIndex = url.indexOf(" ");
        if (spaceIndex > 0 && spaceIndex < url.length()) {
            url = url.substring(0, spaceIndex);
        }

        url = url.trim();
        return url.isEmpty() ? null : url;
    }
}
