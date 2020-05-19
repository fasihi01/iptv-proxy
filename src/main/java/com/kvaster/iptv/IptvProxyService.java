package com.kvaster.iptv;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.kvaster.iptv.config.IptvProxyConfig;
import com.kvaster.utils.digest.Digest;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IptvProxyService implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IptvProxyService.class);

    private final Undertow undertow;
    private final Timer timer = new Timer();

    private final BaseUrl baseUrl;
    private final String tokenSalt;

    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());

    private final List<IptvServer> servers;
    private volatile Map<String, IptvChannel> channels = new HashMap<>();
    private Map<String, IptvServerChannel> serverChannelsByUrl = new HashMap<>();

    private final Map<String, IptvUser> users = new ConcurrentHashMap<>();

    private final boolean allowAnonymous;
    private final Set<String> allowedUsers;

    private final int connectTimeoutSec;

    public IptvProxyService(IptvProxyConfig config) throws Exception {
        baseUrl = new BaseUrl(config.getBaseUrl(), config.getForwardedPass());

        this.tokenSalt = config.getTokenSalt();

        this.allowAnonymous = config.getAllowAnonymous();
        this.allowedUsers = config.getUsers();

        this.connectTimeoutSec = config.getConnectTimeoutSec();

        undertow = Undertow.builder()
                .addHttpListener(config.getPort(), config.getHost())
                .setHandler(this)
                .build();

        List<IptvServer> ss = new ArrayList<>();
        config.getServers().forEach((sc) -> ss.add(new IptvServer(sc)));
        servers = Collections.unmodifiableList(ss);
    }

    public void startService() throws IOException {
        LOG.info("starting");

        undertow.start();
        scheduleChannelsUpdate(1);

        LOG.info("started");
    }

    public void stopService() {
        LOG.info("stopping");

        timer.cancel();
        undertow.stop();

        LOG.info("stopped");
    }

    private void scheduleChannelsUpdate(long delay) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateChannels();
            }
        }, delay);
    }

    private void updateChannels() {
        if (updateChannelsImpl())
            scheduleChannelsUpdate(TimeUnit.MINUTES.toMillis(240));
        else
            scheduleChannelsUpdate(TimeUnit.MINUTES.toMillis(10));
    }

    private boolean updateChannelsImpl() {
        LOG.info("updating channels");

        Map<String, IptvChannel> chs = new HashMap<>();
        Map<String, IptvServerChannel> byUrl = new HashMap<>();

        Digest digest = Digest.sha256();

        for (IptvServer server : servers) {
            LOG.info("loading playlist: {}", server.getName());

            String channels = loadChannels(server.getUrl(), server.getHttpClient());
            if (channels == null) {
                return false;
            }

            String name = null;
            List<String> info = new ArrayList<>(10); // magic number, usually we have only 1-3 lines of info tags

            for (String line: channels.split("\n")) {
                line = line.trim();

                if (line.startsWith("#EXTM3U")) {
                    // do nothing - m3u format tag
                } else if (line.startsWith("#")) {
                    info.add(line);
                    int idx = line.lastIndexOf(',');
                    if (idx >= 0) {
                        name = line.substring(idx + 1);
                    }
                } else {
                    if (name == null) {
                        LOG.warn("skipping malformed channel: {}", line);
                    } else {
                        String id = digest.digest(name);
                        final String _name = name;
                        IptvChannel channel = chs.computeIfAbsent(id, k -> new IptvChannel(id, _name, info.toArray(new String[0])));

                        IptvServerChannel serverChannel = serverChannelsByUrl.get(line);
                        if (serverChannel == null) {
                            serverChannel = new IptvServerChannel(server, line, baseUrl.forPath('/' + id), id, name, connectTimeoutSec, timer);
                        }

                        channel.addServerChannel(serverChannel);

                        chs.put(id, channel);
                        byUrl.put(line, serverChannel);
                    }

                    name = null;
                    info.clear();
                }
            }
        }

        channels = chs;
        serverChannelsByUrl = byUrl;

        LOG.info("channels updated.");

        return true;
    }

    private String loadChannels(String url, HttpClient httpClient) {
        try {
            // TODO we should implement better channels loading retry logic
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(connectTimeoutSec))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != HttpURLConnection.HTTP_OK) {
                LOG.error("can't load playlist - status code is: {}", resp.statusCode());
            } else {
                return resp.body();
            }
        } catch (IOException ie) {
            LOG.error("can't load playlist - io error: {}", ie.getMessage());
        } catch (InterruptedException ie) {
            LOG.error("interrupted while loading playlist");
        }

        return null;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (!handleInternal(exchange)) {
            exchange.setStatusCode(HttpURLConnection.HTTP_NOT_FOUND);
            exchange.getResponseSender().send("N/A");
        }
    }

    private boolean handleInternal(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.startsWith("m3u")) {
            return handleM3u(exchange, path);
        }

        // channels
        int idx = path.indexOf('/');
        if (idx < 0) {
            return false;
        }

        String ch = path.substring(0, idx);
        path = path.substring(idx + 1);

        IptvChannel channel = channels.get(ch);
        if (channel == null) {
            return false;
        }

        // we need user if this is not m3u request
        String token = exchange.getQueryParameters().getOrDefault("t", new ArrayDeque<>()).peek();
        String user = getUserFromToken(token);

        // pass user name from another iptv-proxy
        String proxyUser = exchange.getRequestHeaders().getFirst(IptvServer.PROXY_USER_HEADER);
        if (proxyUser != null) {
            user = user + ':' + proxyUser;
        }

        // no token, or user is not verified
        if (user == null) {
            return false;
        }

        IptvUser iu = users.computeIfAbsent(user, (u) -> new IptvUser(u, timer, users::remove));
        iu.lock();
        try {
            IptvServerChannel serverChannel = iu.getServerChannel(channel);
            if (serverChannel == null) {
                return false;
            }

            return serverChannel.handle(exchange, path, iu, token);
        } finally {
            iu.unlock();
        }
    }

    private String getUserFromToken(String token) {
        if (token == null) {
            return null;
        }

        int idx = token.indexOf('-');
        if (idx < 0) {
            return null;
        }

        String digest = token.substring(idx + 1);
        String user = token.substring(0, idx);

        if (digest.equals(Digest.md5(user + tokenSalt))) {
            return user;
        }

        return null;
    }

    private String generateUser() {
        return String.valueOf(idCounter.incrementAndGet());
    }

    private String  generateToken(String user) {
        return user + '-' + Digest.md5(user + tokenSalt);
    }

    private boolean handleM3u(HttpServerExchange exchange, String path) {
        String user = null;

        int idx = path.indexOf('/');
        if (idx >= 0) {
            user = path.substring(idx + 1);
            if (!allowedUsers.contains(user)) {
                user = null;
            }
        }

        if (user == null && allowAnonymous) {
            user = generateUser();
        }

        if (user == null) {
            return false;
        }

        String token = generateToken(user);

        exchange.getResponseHeaders()
                .add(Headers.CONTENT_TYPE, "audio/mpegurl")
                .add(Headers.CONTENT_DISPOSITION, "attachment; filename=playlist.m3u");

        List<IptvChannel> chs = new ArrayList<>(channels.values());
        chs.sort(Comparator.comparing(IptvChannel::getName));

        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");

        chs.forEach(ch -> {
            for (String i : ch.getInfo()) {
                sb.append(i).append("\n");
            }
            sb.append(baseUrl.getBaseUrl(exchange)).append('/').append(ch.getId()).append("/channel.m3u8?t=").append(token).append("\n");
        });

        exchange.getResponseSender().send(sb.toString());

        return true;
    }
}
