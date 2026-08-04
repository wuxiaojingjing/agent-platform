package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把控制台前端产物挂到 {@code /console/}。
 *
 * <p>产物**不进仓库**。构建产物入库会带来两个后果：每次前端改动都在 diff 里塞进一份压缩过
 * 的 JS，评审时无法阅读；以及仓库里的产物与源码随时可能不同步，而没人能一眼看出来。
 * 所以这里从磁盘上找 {@code frontend/dist}，找不到就不挂——开发期走 Vite 的 5173 端口，
 * 打包部署时由 {@code -Pconsole} 触发前端构建。
 *
 * <p>找不到产物时**只记一行 info 而不报错**：控制台是附属物，缺了它主链路照常服务。
 */
@Configuration
@ConditionalOnProperty(prefix = "huawei.finance.mobile-banking.console", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ConsoleStaticConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ConsoleStaticConfiguration.class);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 前后端都归属同一 Agent：assets 的父目录就是 Agent home，frontend 与它并列。
        // 定位不到产品布局（比如产物挂载到别处）时只记 info，不因附属物拖垮启动。
        Path dist = AgentAssetLocations.findAssets()
                .map(Path::getParent)
                .map(root -> root.resolve("frontend/dist"))
                .orElse(null);
        if (dist != null && Files.isDirectory(dist)
                && Files.isRegularFile(dist.resolve("index.html"))) {
            // 带内容哈希的构建产物可长期缓存；入口 HTML 必须每次确认版本，否则同一 URL
            // 会出现一个标签页加载旧 bundle、另一个标签页加载新 bundle 的割裂状态。
            registry.addResourceHandler("/console/assets/**")
                    .addResourceLocations(dist.resolve("assets").toUri().toString())
                    .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
            registry.addResourceHandler("/console/**")
                    .addResourceLocations(dist.toUri().toString())
                    .setCacheControl(CacheControl.noStore())
                    .resourceChain(false)
                    .addResolver(new IndexFallbackResolver());
            log.info("控制台已挂载 /console/ ← {}", dist);
            return;
        }
        log.info("未找到控制台前端产物，/console/ 不挂载。开发期请在 console/ 下 npm run dev；"
                + "打包请用 mvn -Pconsole package");
    }

    /**
     * 两个入口都得能开：{@code /console} 与 {@code /console/}。
     *
     * <p>目录形式的 {@code /console/} 单靠静态资源处理器是 404——它要的是具体文件，
     * 而空路径在进到资源解析器之前就被判为无效路径了，下面那个回落解析器根本没机会执行。
     * 偏偏人手输的、文档里复制的，几乎都是这个形式。
     *
     * <p>不带斜杠的那个只能重定向不能转发：相对路径的资源会以 {@code /} 为基准去找，
     * 页面会白屏，且控制台里只有几条 404，看不出是斜杠的问题。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/console", "/console/");
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
    }

    /**
     * 目录路径回落到 {@code index.html}。
     *
     * <p>静态资源处理器只认具体文件：{@code /console/index.html} 能开，{@code /console/} 是 404。
     * 而人手输的、以及从文档里复制的，几乎都是后者。
     *
     * <p>只对**看起来像路由**的路径回落——路径里最后一段不带扩展名。带扩展名的一律照常 404：
     * 让一个不存在的 {@code .js} 返回 HTML，浏览器会报一句
     * 「Unexpected token '<'」，那比 404 难查得多。
     */
    private static final class IndexFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            return lastSegment.contains(".") ? null : super.getResource("index.html", location);
        }
    }
}
