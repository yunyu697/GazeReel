/*
 * E6 离线校验：把 NetworkSources.HttpDirectoryScanner 的「取链接」逻辑原样搬到
 * 纯 JVM 上跑，对照旧 href 正则。不依赖 Android / Robolectric。
 *
 * 编译：
 *   javac -cp <jsoup-1.24.1.jar> -d /tmp/e6 tools/HttpDirParseCheck.java
 *   java  -cp /tmp/e6:<jsoup-1.24.1.jar> HttpDirParseCheck
 *
 * 版本必须与 gradle/libs.versions.toml 里的 jsoup 一致，否则这里过了、真机上跑的不是同一份实现。
 *
 * 退出码 0 = 每条用例的预期都成立；非 0 = 有断言失败，别把这个替换发出去。
 */
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpDirParseCheck {

    private static final Pattern OLD = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final String BASE = "http://192.168.1.9:8080/clips/";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // ① 典型的 nginx autoindex：规范双引号，替换前后必须逐条一致。
        String nginx = "<html><head><title>Index of /clips/</title></head><body>"
                + "<a href=\"../\">../</a>"
                + "<a href=\"VID_20260712_143022.mp4\">VID_20260712_143022.mp4</a> 12-Jul-2026 14:30 42M\n"
                + "<a href=\"?C=N;O=D\">Name</a> "
                + "<a href=\"/\">/</a>"
                + "<a href=\"sub/\">sub/</a>"
                + "<a href=\"javascript:void(0)\">x</a>"
                + "</body></html>";
        check("nginx autoindex 逐条一致", nginx, "UTF-8", null,
                oldLinks(nginx), newLinks(nginx, "UTF-8", null));

        // ② 无引号 / 单引号 / HTML 实体 / 大小写混用：旧正则整批漏，jsoup 应全部拿到。
        String messy = "<HTML><BODY>"
                + "<A HREF=GOPR0231.MP4>GOPR0231.MP4</A>"
                + "<a href='GOPR0232.MP4'>GOPR0232.MP4</a>"
                + "<a href=\"Trip&amp;Sunset.mp4\">Trip&amp;Sunset.mp4</a>"
                + "<link href=\"style.css\">"
                + "</BODY></HTML>";
        List<String> messyNew = newLinks(messy, "UTF-8", null);
        // 返回值是解析后的绝对 URL（与生产代码一致），所以用后缀比对。
        expect("无引号 href 命中", endsWith(messyNew, "/GOPR0231.MP4"));
        expect("单引号 href 命中", endsWith(messyNew, "/GOPR0232.MP4"));
        expect("HTML 实体解码成 &", endsWith(messyNew, "/Trip&Sunset.mp4"));
        expect("旧正则确实漏掉无引号", !endsWith(oldLinks(messy), "/GOPR0231.MP4"));

        // ③ 中文 GBK 目录页：旧版写死 UTF-8，文件名全是乱码；新版靠 Content-Type / meta 自探。
        String gbkText = "<html><head><meta charset=\"GBK\"></head><body>"
                + "<a href=\"旅行/2026年七月/\">旅行/2026年七月/</a>"
                + "<a href=\"家庭录像.mp4\">家庭录像.mp4</a>"
                + "</body></html>";
        byte[] gbkBytes = gbkText.getBytes(Charset.forName("GBK"));
        List<String> gbkNew = newLinks(gbkBytes, null, null);
        expect("GBK meta 自探拿到中文", gbkNew.stream().anyMatch(s -> s.contains("家庭录像")));
        expect("GBK 页中文没变问号", gbkNew.stream().anyMatch(s -> s.contains("2026年七月")));

        // ④ 同源 <base href>：相对链接要按 <base> 解析，且 <base> 自己不能被当成条目。
        String withBase = "<html><head><base href=\"http://192.168.1.9:8080/clips/2026/\"></head>"
                + "<body><a href=\"GOPR0233.MP4\">x</a></body></html>";
        List<String> baseNew = newLinks(withBase, "UTF-8", null);
        expect("同源 base 生效",
                baseNew.contains("http://192.168.1.9:8080/clips/2026/GOPR0233.MP4"));
        expect("base 标签本身不是条目",
                baseNew.stream().noneMatch(s -> s.contains("clips/2026/") && s.endsWith("/")));

        // ⑤ 站外 <base> 不采纳：否则整页相对链接会被同源校验挡掉，本来能用的目录变空。
        String evilBase = "<html><head><base href=\"http://evil.example.com/x/\"></head>"
                + "<body><a href=\"GOPR0234.MP4\">x</a></body></html>";
        List<String> evilNew = newLinks(evilBase, "UTF-8", null);
        expect("站外 base 被忽略，链接仍在本机",
                evilNew.contains(BASE + "GOPR0234.MP4"));

        // ⑥ 畸形 HTML（未闭合标签）与带 # 的 href：旧正则会整条丢，新版应保留。
        String broken = "<html><body><a href=\"CLIP.mp4\">a</a><a href=\"B.mp4#t=10\">b</a>"
                + "<a href=\"C.mp4\">c";
        List<String> brokenNew = newLinks(broken, "UTF-8", null);
        expect("未闭合标签仍能解析", endsWith(brokenNew, "/CLIP.mp4"));
        expect("带 # 片段按路径保留", endsWith(brokenNew, "/B.mp4"));
        expect("畸形页没把 C.mp4 吃掉", endsWith(brokenNew, "/C.mp4"));

        System.out.println(failures == 0
                ? "E6 离线校验：全部通过"
                : "E6 离线校验：失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- 旧实现：写死 UTF-8 + href 正则 ----
    private static List<String> oldLinks(String html) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = OLD.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (!acceptable(href)) continue;
            String absolute = resolve(BASE, href);
            if (absolute == null) continue;
            String clean = absolute.split("\\?", 2)[0].split("#", 2)[0];
            if (clean.endsWith(".mp4") || clean.endsWith(".MP4") || clean.endsWith("/")) {
                out.add(clean);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- 新实现：与 NetworkSources.kt 逐行对应 ----
    private static List<String> newLinks(String html, String declared, String ignored) {
        return newLinks(html.getBytes(Charset.forName("UTF-8")), declared, ignored);
    }

    private static List<String> newLinks(byte[] body, String declared, String ignored) {
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(body), declared, BASE);
        } catch (Exception e) {
            try {
                doc = Jsoup.parse(new ByteArrayInputStream(body), null, BASE);
            } catch (Exception e2) {
                throw new RuntimeException(e2);
            }
        }
        String baseHost = hostOf(BASE);
        String declaredBase = null;
        org.jsoup.nodes.Element baseTag = doc.selectFirst("base[href]");
        if (baseTag != null) {
            declaredBase = baseTag.attr("href").trim();
        }
        String effectiveBase = BASE;
        if (declaredBase != null && !declaredBase.isEmpty()) {
            try {
                String candidate = new URL(new URL(BASE), declaredBase).toString();
                if (baseHost == null || baseHost.equals(hostOf(candidate))) {
                    effectiveBase = candidate;
                }
            } catch (Exception ignored2) { }
        }
        Set<String> out = new LinkedHashSet<>();
        for (org.jsoup.nodes.Element link : doc.select("[href]")) {
            if (link.tagName().equalsIgnoreCase("base")) continue;
            String href = link.attr("href").trim().split("#", 2)[0];
            if (!acceptable(href)) continue;
            String absolute = resolve(effectiveBase, href);
            if (absolute == null) continue;
            String clean = absolute.split("\\?", 2)[0].split("#", 2)[0];
            if (clean.endsWith(".mp4") || clean.endsWith(".MP4") || clean.endsWith("/")) {
                out.add(clean);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean acceptable(String href) {
        return !href.isEmpty()
                && !href.startsWith("?") && !href.startsWith("#")
                && !href.toLowerCase().startsWith("javascript:")
                && !href.startsWith("../")
                && !href.equals("/") && !href.equals("./") && !href.equals("../");
    }

    private static String resolve(String base, String href) {
        try {
            String absolute = new URL(new URL(base), href).toString();
            URL resolved = new URL(absolute);
            String baseHost = hostOf(base);
            if (baseHost != null && !resolved.getHost().equalsIgnoreCase(baseHost)) return null;
            return absolute;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return null; }
    }

    private static void check(String label, String html, String charset, String ignored,
                              List<String> before, List<String> after) {
        boolean same = before.equals(after);
        System.out.println("[" + (same ? "OK" : "!!") + "] " + label
                + "\n      旧=" + before + "\n      新=" + after);
        expect(label, same);
    }

    private static boolean endsWith(List<String> urls, String suffix) {
        return urls.stream().anyMatch(s -> s.endsWith(suffix));
    }

    private static void expect(String label, boolean ok) {
        System.out.println("[" + (ok ? "OK" : "!!") + "] " + label);
        if (!ok) failures++;
    }
}
