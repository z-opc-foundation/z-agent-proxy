package com.zifang.z.agent.proxy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README 定量主张自守卫。
 *
 * <p>判据：README 里写死的数字（模块数 / 版本号 / 类数 / 方法数 / 端口 / 源码常量 / 文件数）
 * 必须能从源码重算出来 —— README 改了就红，源码变了 README 没跟上也得红。</p>
 *
 * <p>口径约定（踩过的坑，勿随意改）：</p>
 * <ol>
 *   <li>抠不到就红：README 主张里的数字抠不出来、或锚点命中 0 行/多行，一律红 ——
 *       「没匹配上就跳过」正是空跑，尺子一旦学会放弃就等于没有尺子；</li>
 *   <li>定位用锚点（表头关键字 / 结构树节点名 + 树形前缀），不拿数字本身当锚点 ——
 *       短数字是长数字的子串（{@code 6} ⊂ {@code 16}），按数字定位会匹配到别人的行上；</li>
 *   <li>根 pom 的 version / parent version / module 数走 XML 解析：正则会抓到 {@code <parent>} 里的
 *       {@code <version>}，也躲不掉被 {@code <!-- -->} 注释掉的 {@code <module>}；
 *       CI-friendly 版本（{@code ${revision}}）读 {@code <revision>} 属性；</li>
 *   <li>数源码前先剥注释：Javadoc 里出现的 {@code @Test} / 方法名字样会被当成真声明；</li>
 *   <li>本仓是单模块仓：{@code <module>} 数为 0，不按目录名统计；</li>
 *   <li>数「受控文件」用 {@code git ls-files}（README 说的是 git 跟踪的文件，不是磁盘上的杂物）。</li>
 * </ol>
 *
 * <p>数值不等 = 红，且报错把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 尺子的牙口由 {@link #mutatedReadmeMustBeRejected()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #healthyReadmeMustNotBeCondemned()} 证明。</p>
 */
class ReadmeClaimsTest {

    // ------------------------------------------------------------------ 仓库里的固定位置

    private static final String SPI_REL = "src/main/java/com/zifang/z/agent/proxy/spi/ThirdPartyAgentAdapter.java";
    private static final String AIDER_REL = "src/main/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapter.java";
    private static final String CURSOR_REL = "src/main/java/com/zifang/z/agent/proxy/adapter/cursor/CursorAdapter.java";
    private static final String MAIN_JAVA_REL = "src/main/java";

    // ------------------------------------------------------------------ 定位与读取

    /** 仓根：测试跑在模块目录，user.dir 不是仓根 —— 向上找同时有 README.md 与 pom.xml 的目录（最多 5 层）。 */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("README.md")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("定位仓根失败：从 " + System.getProperty("user.dir")
                + " 向上 5 层内没有同时含 README.md 与 pom.xml 的目录");
    }

    /** README 全文（统一成 \n 换行，便于按行算偏移）。 */
    private static String readmeText() {
        try {
            String raw = new String(Files.readAllBytes(repoRoot().resolve("README.md")), StandardCharsets.UTF_8);
            return raw.replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("读取 README.md 失败", e);
        }
    }

    private static String readText(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("读取 " + path + " 失败", e);
        }
    }

    // ------------------------------------------------------------------ 源码重算

    /** 剥掉块注释与行注释：Javadoc 里出现的注解字样/方法名会被当成真声明。 */
    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = (i + 1 < src.length()) ? src.charAt(i + 1) : '\0';
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    sb.append(c);
                }
                continue;
            }
            if (inStr) {
                sb.append(c);
                if (c == '\\') {
                    sb.append(n);
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                sb.append(c);
                if (c == '\\') {
                    sb.append(n);
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static List<Path> javaFiles(Path base) {
        if (!Files.isDirectory(base)) {
            throw new AssertionError("源码目录不存在：" + base);
        }
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(base)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().endsWith(".java"))::iterator) {
                out.add(p);
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 " + base + " 失败", e);
        }
        return out;
    }

    /** src/main 下实现 ThirdPartyAgentAdapter 的类数（按「implements」判，不按目录个数）。 */
    private static int adapterImplementationCount() {
        int count = 0;
        for (Path p : javaFiles(repoRoot().resolve(MAIN_JAVA_REL))) {
            if (stripComments(readText(p)).contains("implements ThirdPartyAgentAdapter")) {
                count++;
            }
        }
        return count;
    }

    /** SPI 接口自己声明的方法数（不含继承自 kernel Agent 的那 4 个）。 */
    private static int spiDeclaredMethodCount() {
        String src = stripComments(readText(repoRoot().resolve(SPI_REL)));
        Matcher m = Pattern.compile("(?m)^[ \\t]*[^;{}()]*\\([^()]*\\)\\s*;").matcher(src);
        int count = 0;
        while (m.find()) {
            count++;
        }
        if (count == 0) {
            throw new AssertionError("SPI 里一个方法声明都没数到，尺子口径坏了：" + SPI_REL);
        }
        return count;
    }

    /** AiderAdapter 的默认超时（源码里的 long 字面量，形如 300_000L）。 */
    private static String aiderDefaultTimeoutMs() {
        String src = stripComments(readText(repoRoot().resolve(AIDER_REL)));
        Set<String> found = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("(?<![\\w.])(\\d[\\d_]*)L\\b").matcher(src);
        while (m.find()) {
            found.add(m.group(1));
        }
        if (found.size() != 1) {
            throw new AssertionError("AiderAdapter 里的 long 字面量不唯一（读到 " + found + "），尺子口径坏了：" + AIDER_REL);
        }
        return found.iterator().next();
    }

    /** CursorAdapter 缺省 endpoint 的端口。 */
    private static String cursorDefaultEndpointPort() {
        String src = stripComments(readText(repoRoot().resolve(CURSOR_REL)));
        Matcher m = Pattern.compile("\"http://localhost:(\\d+)[^\"]*\"").matcher(src);
        if (!m.find()) {
            throw new AssertionError("CursorAdapter 里找不到缺省 endpoint 字面量，尺子口径坏了：" + CURSOR_REL);
        }
        String port = m.group(1);
        if (m.find()) {
            throw new AssertionError("CursorAdapter 里有多个缺省 endpoint 字面量，尺子口径坏了：" + CURSOR_REL);
        }
        return port;
    }

    /** _frontend 下 git 跟踪（受控）的文件数。 */
    private static int frontendTrackedFileCount() {
        List<String> out = runCommand(Arrays.asList(
                "git", "-C", repoRoot().toString(), "ls-files", "--", "_frontend"));
        return out.size();
    }

    /** 跑一条只读命令；失败 = 守卫红（拿不到源码真相时绝不假装通过）。 */
    private static List<String> runCommand(List<String> cmd) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new AssertionError("命令起不来：" + cmd, e);
        }
        List<String> out = new ArrayList<String>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                throw new AssertionError("命令失败（exit " + exit + "）：" + cmd
                        + " —— 读不到源码真相，守卫必须红而不是空跑");
            }
        } catch (IOException e) {
            throw new AssertionError("读命令输出失败：" + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("命令被中断：" + cmd, e);
        }
        return out;
    }

    // ------------------------------------------------------------------ 根 pom（XML 解析）

    private static Element rootProject() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(repoRoot().resolve("pom.xml").toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new AssertionError("解析根 pom.xml 失败", e);
        }
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** 根 pom 的版本号；CI-friendly（${revision}）时读 <revision> 属性。 */
    private static String rootPomVersion() {
        Element project = rootProject();
        Element version = directChild(project, "version");
        if (version == null) {
            throw new AssertionError("根 pom.xml 没有 <version>");
        }
        String raw = version.getTextContent().trim();
        if (raw.contains("${revision}")) {
            Element properties = directChild(project, "properties");
            Element revision = properties == null ? null : directChild(properties, "revision");
            if (revision == null) {
                throw new AssertionError("根 pom.xml 用 ${revision} 但没有 <revision> 属性");
            }
            return revision.getTextContent().trim();
        }
        return raw;
    }

    /** 根 pom 的父项目版本。 */
    private static String rootPomParentVersion() {
        Element parent = directChild(rootProject(), "parent");
        if (parent == null) {
            throw new AssertionError("根 pom.xml 没有 <parent>");
        }
        Element version = directChild(parent, "version");
        if (version == null) {
            throw new AssertionError("根 pom.xml 的 <parent> 没有 <version>");
        }
        return version.getTextContent().trim();
    }

    /** 根 pom 里实际参与构建的 module 数（没有 <modules> 即 0；被注释掉的 module 不算）。 */
    private static int rootPomModuleCount() {
        NodeList modules = rootProject().getElementsByTagName("module");
        return modules.getLength();
    }

    // ------------------------------------------------------------------ README 主张抽取

    /** 主张所在文本块：全文区间 + 文本。 */
    private static final class Block {
        final int start;
        final int end;
        final String text;

        Block(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /** 主张定位：把 README 全文映射到一个唯一的文本块。 */
    private interface Anchor {
        Block locate(String readme, String label);
    }

    /** 一条定量主张：定位锚点 + 抠数正则 + 源码重算口径。 */
    private static final class Claim {
        final String label;
        final Anchor anchor;
        final Pattern number;
        final Supplier<String> expected;

        Claim(String label, Anchor anchor, Pattern number, Supplier<String> expected) {
            this.label = label;
            this.anchor = anchor;
            this.number = number;
            this.expected = expected;
        }
    }

    private static List<String> lines(String readme) {
        return Arrays.asList(readme.split("\n", -1));
    }

    private static int lineStart(String readme, int lineIndex) {
        int idx = 0;
        for (int i = 0; i < lineIndex; i++) {
            int nl = readme.indexOf('\n', idx);
            if (nl < 0) {
                return readme.length();
            }
            idx = nl + 1;
        }
        return idx;
    }

    /** 定位 README 中唯一匹配的行；找不到或不唯一都红。 */
    private static Block uniqueLine(String readme, Pattern anchor, String label) {
        List<String> all = lines(readme);
        List<Integer> hits = new ArrayList<Integer>();
        for (int i = 0; i < all.size(); i++) {
            if (anchor.matcher(all.get(i)).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw new AssertionError("README 里找不到主张行：" + label + "（定位 " + anchor.pattern() + "）");
        }
        if (hits.size() > 1) {
            throw new AssertionError("README 里主张行定位不唯一（命中 " + hits.size() + " 行）：" + label);
        }
        int i = hits.get(0);
        int start = lineStart(readme, i);
        return new Block(start, start + all.get(i).length(), all.get(i));
    }

    /**
     * 定位 README 结构树里的节点行（树形前缀 + 节点名），并把它后面紧跟的续行（│ 开头）并入同一块。
     * 节点名带终止条件，避免 spi/ThirdPartyAgentAdapter.java 误配别的同名节点。
     */
    private static Block treeNodeBlock(String readme, String nodeName, String label) {
        List<String> all = lines(readme);
        Pattern node = Pattern.compile("^[\\s\u2502\u2500]*(?:\u251c\u2500\u2500|\u2514\u2500\u2500)\\s*"
                + Pattern.quote(nodeName) + "(?![\\w-])");
        List<Integer> hits = new ArrayList<Integer>();
        for (int i = 0; i < all.size(); i++) {
            if (node.matcher(all.get(i)).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw new AssertionError("README 结构树里找不到节点：" + label + "（" + nodeName + "）");
        }
        if (hits.size() > 1) {
            throw new AssertionError("README 结构树里节点定位不唯一（命中 " + hits.size() + " 处）：" + label);
        }
        int first = hits.get(0);
        int last = first;
        while (last + 1 < all.size() && all.get(last + 1).matches("^\\s*\u2502.*$")) {
            last++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                sb.append('\n');
            }
            sb.append(all.get(i));
        }
        int start = lineStart(readme, first);
        int end = lineStart(readme, last) + all.get(last).length();
        return new Block(start, end, sb.toString());
    }

    /** 从主张块里抠数字；抠不到就红（不跳过后续主张）。 */
    private static int[] locateNumber(Block block, Pattern number, String label) {
        Matcher m = number.matcher(block.text);
        if (!m.find()) {
            throw new AssertionError("README 主张里抠不到数字：" + label + "（正则 " + number.pattern() + "）");
        }
        return new int[]{block.start + m.start(1), block.start + m.end(1)};
    }

    // ------------------------------------------------------------------ 主张表

    private static List<Claim> claims() {
        List<Claim> list = new ArrayList<Claim>();

        // 1. 基本信息表：单模块仓，无 reactor 子模块（<module> 数为 0）。
        list.add(new Claim("reactor 子模块数",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*\\*\\*仓库\\*\\*"), label),
                Pattern.compile("\u21d2\\s*`(\\d+)`"),
                () -> String.valueOf(rootPomModuleCount())));

        // 2. 基本信息表：当前版本（CI-friendly，读 <revision>）。
        list.add(new Claim("当前版本",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*\\*\\*当前版本\\*\\*"), label),
                Pattern.compile("^\\|\\s*\\*\\*当前版本\\*\\*\\s*\\|\\s*`([0-9]+(?:\\.[0-9]+)*)`"),
                ReadmeClaimsTest::rootPomVersion));

        // 3. 基本信息表：父项目版本。
        list.add(new Claim("父项目版本",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*\\*\\*父项目\\*\\*"), label),
                Pattern.compile("z-boot-parent:([0-9]+(?:\\.[0-9]+)*)"),
                ReadmeClaimsTest::rootPomParentVersion));

        // 4. 能力清单：实现共几个 adapter。
        list.add(new Claim("adapter 实现数（能力清单）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^实现共"), label),
                Pattern.compile("实现共\\s*\\*\\*(\\d+)\\*\\*"),
                () -> String.valueOf(adapterImplementationCount())));

        // 4'. 「明确没做的」一节里的同一主张的第二处表述。
        list.add(new Claim("adapter 实现数（未做清单）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("实现只有"), label),
                Pattern.compile("实现只有\\s*(\\d+)\\s*家"),
                () -> String.valueOf(adapterImplementationCount())));

        // 5. 能力清单：SPI 额外要求几个方法。
        list.add(new Claim("SPI 额外方法数（能力清单）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("再额外要求"), label),
                Pattern.compile("额外要求\\s*(\\d+)\\s*个方法"),
                () -> String.valueOf(spiDeclaredMethodCount())));

        // 5'. 项目结构树里 SPI 节点的注释 —— 同一主张的第二处表述。
        list.add(new Claim("SPI 额外方法数（结构树）",
                (readme, label) -> treeNodeBlock(readme, "spi/ThirdPartyAgentAdapter.java", label),
                Pattern.compile("Agent\\s*\\+\\s*(\\d+)\\s*个额外方法"),
                () -> String.valueOf(spiDeclaredMethodCount())));

        // 6. 能力清单：Aider 缺省超时（源码里的 long 字面量）。
        list.add(new Claim("Aider 缺省超时",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*`AiderAdapter`\\s*\\|"), label),
                Pattern.compile("默认超时\\s*`(\\d[\\d_]*)ms`"),
                ReadmeClaimsTest::aiderDefaultTimeoutMs));

        // 7. 能力清单：Cursor 缺省 endpoint 的端口。
        list.add(new Claim("Cursor 缺省端口（能力清单）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*`CursorAdapter`\\s*\\|"), label),
                Pattern.compile("localhost:(\\d+)"),
                ReadmeClaimsTest::cursorDefaultEndpointPort));

        // 7'. 对外接口表里的同一端口 —— 同一主张的第二处表述。
        list.add(new Claim("Cursor 缺省端口（对外接口表）",
                (readme, label) -> uniqueLine(readme, Pattern.compile("^\\|\\s*出站 HTTP\\s*\\|"), label),
                Pattern.compile("localhost:(\\d+)"),
                ReadmeClaimsTest::cursorDefaultEndpointPort));

        // 8. 项目结构树：_frontend 下几个受控文件。
        list.add(new Claim("前端受控文件数（结构树）",
                (readme, label) -> treeNodeBlock(readme, "_frontend/z-agent-proxy-suit/", label),
                Pattern.compile("(\\d+)\\s*个受控文件"),
                () -> String.valueOf(frontendTrackedFileCount())));

        // 8'. 项目结构说明里的同一份文件清单 —— 同一主张的第二处表述。
        list.add(new Claim("前端受控文件数（文件清单段）",
                (readme, label) -> uniqueLine(readme,
                        Pattern.compile("_frontend/z-agent-proxy-suit/`\\s*下"), label),
                Pattern.compile("下\\s*\\**\\s*(\\d+)"),
                () -> String.valueOf(frontendTrackedFileCount())));

        return list;
    }

    /** 逐条对账：README 的数字 == 源码重算值。 */
    private static void checkClaims(String readme, List<Claim> claims) {
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            int[] span = locateNumber(block, claim.number, claim.label);
            String actual = readme.substring(span[0], span[1]);
            String expected = claim.expected.get();
            assertEquals(expected, actual,
                    claim.label + " —— README 写 " + actual + "，源码重算 " + expected);
        }
    }

    /** 数字 +1（末段自增，保留下划线分组），用于合成「把某个数改错」的 README。 */
    private static String shiftNumber(String raw) {
        Matcher m = Pattern.compile("\\d+(?:_\\d+)*").matcher(raw);
        String last = null;
        int lastStart = -1;
        int lastEnd = -1;
        while (m.find()) {
            last = m.group();
            lastStart = m.start();
            lastEnd = m.end();
        }
        if (last == null) {
            throw new AssertionError("主张里没有可变异的数字：" + raw);
        }
        String[] groups = last.split("_");
        int tail = Integer.parseInt(groups[groups.length - 1]) + 1;
        groups[groups.length - 1] = String.valueOf(tail);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.length; i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(groups[i]);
        }
        return raw.substring(0, lastStart) + sb + raw.substring(lastEnd);
    }

    // ------------------------------------------------------------------ 用例

    /** 用例 A：真实 README 的所有主张与源码重算值逐条对账 —— 必须全绿。 */
    @Test
    void readmeQuantitativeClaimsMustMatchSource() {
        checkClaims(readmeText(), claims());
    }

    /** 用例 B（牙口）：每个主张都合成一个改错数字的 README，必须抛 AssertionError，且报错指向那条主张。 */
    @Test
    void mutatedReadmeMustBeRejected() {
        String readme = readmeText();
        List<Claim> claims = claims();
        // 基线必须先健康，否则「报错指向哪条主张」的判断没意义
        assertDoesNotThrow(() -> checkClaims(readme, claims), "基线 README 自身已漂移，先把 README 修回与源码一致");
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            int[] span = locateNumber(block, claim.number, claim.label);
            String original = readme.substring(span[0], span[1]);
            String mutated = shiftNumber(original);
            assertNotEquals(original, mutated, "变异没有生效：" + claim.label);
            String broken = readme.substring(0, span[0]) + mutated + readme.substring(span[1]);
            AssertionError error = assertThrows(AssertionError.class, () -> checkClaims(broken, claims),
                    "主张被放过（README 写错但没红）：" + claim.label);
            assertTrue(error.getMessage() != null && error.getMessage().contains(claim.label),
                    "报错没有指向真正对不上的那条主张（期望含「" + claim.label + "」，实得：" + error.getMessage() + "）");
        }
    }

    /** 用例 C（不许冤枉健康页）：真实 README 不得被判红，且尺子不是空转。 */
    @Test
    void healthyReadmeMustNotBeCondemned() {
        String readme = readmeText();
        List<Claim> claims = claims();
        assertTrue(claims.size() >= 3 && claims.size() <= 16,
                "主张表条数异常（读到 " + claims.size() + " 条）：少于 3 条是空转，多于 16 条说明守卫该瘦身或该拆仓");
        assertDoesNotThrow(() -> checkClaims(readme, claims), "真实 README 被判红（尺子冤枉健康页）");
        for (Claim claim : claims) {
            Block block = claim.anchor.locate(readme, claim.label);
            int[] span = locateNumber(block, claim.number, claim.label);
            assertTrue(span[1] > span[0], "主张里抠到的数字是空的：" + claim.label);
        }
    }
}
