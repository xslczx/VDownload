package com.xslczx.vdownload.utils;

public final class ClipboardUtils {

    // 从文本中提取链接部分，去除中文、标点和特定年份路径
    public static String extractCleanUrl(String text) {
        if (text == null || !text.contains("http")) return "";

        String url = text.substring(text.indexOf("http"));

        // 移除汉字
        int end = 0;
        for (; end < url.length(); end++) {
            char c = url.charAt(end);
            if (c >= '一' && c <= '龥') break;
        }
        if (end > 0) {
            url = url.substring(0, end);
        }

        // 移除中文逗号
        url = url.replace("，", "");

        // 移除包含的年份路径
        for (String year : new String[]{"2023/", "2024/", "2025/", "2026/"}) {
            int index = url.indexOf(year);
            if (index > 0) {
                url = url.substring(0, index);
            }
        }

        // 移除空格之后的内容
        int spaceIndex = url.indexOf(" ");
        if (spaceIndex > 0 && spaceIndex < url.length()) {
            url = url.substring(0, spaceIndex);
        }

        return url;
    }
}
