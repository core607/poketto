package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Integration-runtime-only stand-in for GitHub's repository selection screen. */
@RestController
@ConditionalOnProperty(name = "poketto.acceptance.github", havingValue = "true")
public class AcceptanceGitHubController {
    private static final String ROUTE = "/api/auth/workspaces/github/fixture-installation";
    private final JdbcTemplate jdbc;
    private final AcceptanceGitHubConfiguration.Fixture fixture;

    AcceptanceGitHubController(JdbcTemplate jdbc, AcceptanceGitHubConfiguration.Fixture fixture) {
        this.jdbc = jdbc;
        this.fixture = fixture;
    }

    @GetMapping(value = ROUTE, produces = MediaType.TEXT_HTML_VALUE)
    String selection(
            @AuthenticationPrincipal AuthPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        // Native HTML form navigation needs a same-origin Origin; API no-referrer can produce Origin: null.
        response.setHeader("Referrer-Policy", "same-origin");
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        List<String> options = jdbc.query(
                "select repository_id,repository_name from space_github_creation_attempts where account_id=? and repository_id is not null",
                (row, number) -> "<label><input type=radio name=repository required value=\"" + row.getLong(1) + "\">"
                        + HtmlUtils.htmlEscape(row.getString(2)) + "</label><br>",
                actor.accountId());
        return "<!doctype html><html lang=zh><meta charset=utf-8><title>模拟 GitHub 仓库授权</title>"
                + "<h1>模拟 GitHub 仓库授权</h1><p>仅隔离验收使用，不会访问 GitHub。</p><form method=post action=\"" + ROUTE + "\">"
                + "<input type=hidden name=\"" + HtmlUtils.htmlEscape(csrf.getParameterName()) + "\" value=\""
                + HtmlUtils.htmlEscape(csrf.getToken()) + "\">" + String.join("", options)
                + "<button>保存仓库授权</button></form></html>";
    }

    @PostMapping(value = ROUTE, produces = MediaType.TEXT_HTML_VALUE)
    String select(@AuthenticationPrincipal AuthPrincipal actor, @RequestParam long repository) {
        Integer count = jdbc.queryForObject(
                "select count(*) from space_github_creation_attempts where account_id=? and repository_id=?",
                Integer.class,
                actor.accountId(),
                repository);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Unknown acceptance repository");
        }
        fixture.selected.add(repository);
        return "<!doctype html><html lang=zh><meta charset=utf-8><title>模拟授权已保存</title>"
                + "<h1>模拟授权已保存</h1><p>请返回原标签页继续准备空间。</p></html>";
    }
}
