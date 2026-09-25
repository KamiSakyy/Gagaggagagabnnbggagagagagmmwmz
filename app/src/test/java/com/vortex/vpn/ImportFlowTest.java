package com.vortex.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.core.SubscriptionUpdater;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.sub.B64;
import com.vortex.vpn.sub.PageImporter;
import com.vortex.vpn.sub.SubImporter;
import com.vortex.vpn.ui.ServersActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Covers the two failures users hit first: a provider page that must be "unpacked" into
 * locations, and opening the location list once those locations exist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ImportFlowTest {

    private static final String VLESS =
            "vless://0f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e11@example.com:443"
                    + "?security=reality&pbk=8hRk3Q0m5m3XhI1K6m3n0dHIZt6WZ1nYk5K0aVpG1S0"
                    + "&sid=6ba85179e30d4fc2&fp=chrome&flow=xtls-rprx-vision&type=tcp"
                    + "&sni=example.com#%F0%9F%87%A9%F0%9F%87%AA%20Germany";
    private static final String TROJAN =
            "trojan://trojan-password@trojan.example.com:443?security=tls&sni=trojan.example.com"
                    + "&type=ws&path=%2Ftrojan#%F0%9F%87%B3%F0%9F%87%B1%20Netherlands";

    /** A landing page shaped like the ones VPN bots hand out. */
    private static String landingPage(String token) {
        return "<!DOCTYPE html><html><head><title>VPN</title>"
                + "<meta name=\"description\" content=\"VPN access\"></head><body>"
                + "<div class=\"card\"><h4>VPN</h4><p>7203544454_3953</p>"
                + "<p>Истекает через 4 дня</p></div>"
                + "<div class=\"install\"><button data-clipboard-text=\"https://provider.example/"
                + token + "\">Добавить подписку</button>"
                + "<a class=\"btn\" href=\"https://provider.example/" + token + "/install\">Скачать</a>"
                + "<button id=\"copy\" onclick=\"copyLink('https://provider.example/" + token
                + "')\">Скопировать ссылку</button>"
                + "<script>window.__DATA__ = {\"profile\":\"https://provider.example/sub/"
                + token + "\"};</script></div></body></html>";
    }

    @Test
    public void landingPageIsUnpackedIntoLocations() {
        String token = "demoToken123456";
        String page = landingPage(token);
        assertTrue("the provider page must be recognised as HTML", PageImporter.looksLikeHtml(page));

        List<PageImporter.Candidate> candidates =
                PageImporter.candidates("https://provider.example/" + token, page);
        assertFalse("no candidate addresses were found", candidates.isEmpty());

        boolean hasPanelPath = false;
        for (PageImporter.Candidate candidate : candidates) {
            if (candidate.url.endsWith("/sub/" + token)) {
                hasPanelPath = true;
            }
        }
        assertTrue("the /sub/<token> panel address is missing: " + candidates, hasPanelPath);

        // The page itself contains no share links, so the importer must fall back to the
        // panel address - this is what makes such subscriptions work.
        assertEquals(0, SubImporter.parse(page).servers.size());
    }

    @Test
    public void everySubscriptionFormatIsUnderstood() {
        // plain list
        assertEquals(2, SubImporter.parse(VLESS + "\n" + TROJAN).servers.size());
        // base64 wrapped list (the v2ray standard)
        String base64 = B64.encode((VLESS + "\n" + TROJAN).getBytes(StandardCharsets.UTF_8));
        assertEquals(2, SubImporter.parse(base64).servers.size());
        // json array and json object
        assertEquals(1, SubImporter.parse("[\"" + VLESS + "\"]").servers.size());
        assertEquals(1, SubImporter.parse("{\"servers\":[\"" + TROJAN + "\"]}").servers.size());
        // deep link wrapping
        String deep = "v2rayng://install-config?url="
                + java.net.URLEncoder.encode("https://provider.example/sub/abc123token");
        List<String> deepLinks = PageImporter.deepLinks(deep);
        assertEquals(1, deepLinks.size());
        assertEquals("https://provider.example/sub/abc123token", deepLinks.get(0));
        // html with embedded share links still works
        String htmlWithLinks = "<html><body><a href=\"" + VLESS + "\">one</a>"
                + "<textarea>" + TROJAN + "</textarea></body></html>";
        assertEquals(2, SubImporter.parse(htmlWithLinks).servers.size());
    }

    /** The exact path that used to crash with ClassCastException during import. */
    @Test
    public void importStoresServersAndLocationListOpens() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();

        Subscription subscription = new Subscription();
        subscription.name = "test";
        subscription.url = "";
        long id = Repo.insertSubscription(context, subscription);
        subscription.id = id;

        SubscriptionUpdater.Result result = SubscriptionUpdater.store(context, subscription,
                VLESS + "\n" + TROJAN);
        assertTrue("import reported: " + result.message, result.ok);
        assertEquals(2, result.imported);

        List<Server> servers = Repo.servers(context);
        assertEquals("servers were not stored", 2, servers.size());

        // The crash happened here for the user: opening the location list with real data.
        ServersActivity activity = Robolectric.buildActivity(ServersActivity.class).setup().get();
        ViewGroup root = activity.findViewById(android.R.id.content);
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY);
        root.measure(width, height);
        root.layout(0, 0, 1080, 1920);

        RecyclerView list = activity.findViewById(R.id.list);
        assertNotNull(list);
        assertEquals("the list does not show the imported locations", 2,
                list.getAdapter().getItemCount());
        // Force the adapter to bind its rows (this is what crashed on the device).
        list.measure(width, height);
        list.layout(0, 0, 1080, 1920);
        assertTrue("no rows were bound", list.getChildCount() > 0);
    }

    /** Providers that hand out a javascript app instead of plain links. */
    @Test
    public void javascriptLandingPageIsUnpacked() {
        String token = "ghufjskZ9RVk5CTy";
        String page = "<!DOCTYPE html><html><head><script src=\"/assets/app.js\"></script></head>"
                + "<body><div id=\"root\"></div><script>"
                + "const api = '/api/v1/client/subscribe?token=" + token + "';"
                + "const theme = '/assets/style.css';"
                + "window.__SUB__ = \"https://provider.example/sub/" + token + "\";"
                + "</script></body></html>";

        List<PageImporter.Candidate> candidates =
                PageImporter.candidates("https://provider.example/" + token, page);
        boolean hasRelativeApi = false;
        for (PageImporter.Candidate candidate : candidates) {
            if (candidate.url.equals("https://provider.example/api/v1/client/subscribe?token=" + token)) {
                hasRelativeApi = true;
            }
        }
        assertTrue("a relative subscription endpoint was missed: " + candidates, hasRelativeApi);
        // static assets must not waste the request budget
        for (PageImporter.Candidate candidate : candidates) {
            assertFalse("a css file was treated as a subscription: " + candidate,
                    candidate.url.endsWith(".css"));
        }
    }

    /** Some pages keep the payload base64 encoded instead of linking to it. */
    @Test
    public void base64PayloadInsideThePageIsUnpacked() {
        String payload = VLESS + "\n" + TROJAN;
        String blob = B64.encode(payload.getBytes(StandardCharsets.UTF_8));
        String page = "<!DOCTYPE html><html><body><div data-config=\"" + blob
                + "\"></div></body></html>";

        assertEquals("the base64 payload was not unpacked", 2, SubImporter.parse(page).servers.size());
    }

    @Test
    public void storeNeverThrowsOnGarbage() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        for (String garbage : Arrays.asList("", "   ", "<html></html>", "not a subscription",
                "{\"unexpected\":true}", new String(new byte[]{0, 1, 2, 3}))) {
            Subscription subscription = new Subscription();
            subscription.name = "garbage";
            subscription.id = Repo.insertSubscription(context, subscription);
            SubscriptionUpdater.Result result = SubscriptionUpdater.store(context, subscription, garbage);
            assertNotNull(result);
            assertFalse(result.message == null);
        }
    }
}
