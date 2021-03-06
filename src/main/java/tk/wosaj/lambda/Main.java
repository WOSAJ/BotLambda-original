package tk.wosaj.lambda;

import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tk.wosaj.lambda.commands.CommandManager;
import tk.wosaj.lambda.database.guild.GuildService;
import tk.wosaj.lambda.js.JSManager;
import tk.wosaj.lambda.server.Contexts;
import tk.wosaj.lambda.server.ServerStatus;
import tk.wosaj.lambda.util.ResourceLoader;
import tk.wosaj.lambda.util.StaticResourceLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class Main {
    public static JDA bot;
    public static CommandManager manager;
    public static JSManager js;
    public static HttpServer server;
    public static ServerStatus status = ServerStatus.LOADING;
    public static final String defaultPrefix = "?";
    public static final Properties serverProperties = new Properties();
    public static final ResourceLoader loader = new StaticResourceLoader(Main.class);
    public static final Map<String, String> emotes = new HashMap<>();
    public static final PrintStream out;
    public static final PrintStream err;
    public static String consoleCollector = "";
    public static volatile boolean canReadCollector = true;

    private static final Logger logger;

    private Main() {}

    static {
        out = System.out;
        err = System.err;
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                canReadCollector = false;
                out.write(b);
                consoleCollector += (char) b;
                if((char) b == '\n') canReadCollector = true;
            }
        }));
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                canReadCollector = false;
                err.write(b);
                consoleCollector += (char) b;
                if((char) b == '\n') canReadCollector = true;
            }
        }));
        logger = LoggerFactory.getLogger(Main.class);
        logger.info(System.getenv("JDBC_DATABASE_URL"));
        try {
            serverProperties.load(Main.class.getClassLoader().getResourceAsStream("properties/server.properties"));
            Properties emotes0 = new Properties();
            emotes0.load(Main.class.getClassLoader().getResourceAsStream("emotes.properties"));
            emotes0.stringPropertyNames().forEach(key -> emotes.put(key, emotes0.getProperty(key)));
            server = HttpServer.create(
                    new InetSocketAddress(
                            serverProperties.getProperty("ip"),
                            Integer.parseInt(System.getenv("PORT"))), 0);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Thread botThread = new Thread() {{
                setName("Client Thread");
            }
            private boolean loaded;

            @Override
            public void run() {
                try {
                    pingAnother();
                    bot = JDABuilder.createDefault(System.getenv("TOKEN"))
                            .setStatus(OnlineStatus.ONLINE)
                            .setActivity(Activity.playing("/help for help"))
                            .enableIntents(Collections.singletonList(GatewayIntent.GUILD_MEMBERS))
                            .setChunkingFilter(ChunkingFilter.ALL)
                            .setMemberCachePolicy(MemberCachePolicy.ALL)
                            .build();
                    manager = new CommandManager(bot, defaultPrefix);
                    js = new JSManager();
                    bot.awaitReady();
                    status = ServerStatus.ONLINE;
                    loaded = true;
                    new Thread(() -> new GuildService().getAll().forEach(g ->
                            logger.trace("{}: {}", g.getName(), g.getJson())), "Optimizer").start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void interrupt() {
                super.interrupt();
                if (loaded) bot.shutdown();
            }
        };
        Thread serverThread = new Thread() {
            {
                setName("Server Thread");
            }
            @Override
            public void run() {
                Contexts.addContexts(server);
                server.start();
            }

            @Override
            public void interrupt() {
                super.interrupt();
                server.stop(0);
            }
        };
        serverThread.start();
        botThread.start();
        synchronized (Main.class) {
            Main.class.wait();
            serverThread.interrupt();
            serverThread.join();
            logger.info("Server stopped");
            botThread.interrupt();
            botThread.join();
            manager.shutdown();
            logger.info("Bot stopped");
        }
    }

    @SuppressWarnings("BusyWait")
    private static void pingAnother() {
        try {
            String url, url1, url2;
            int host = Integer.parseInt(System.getenv("HOSTNUM"));
            url1 = serverProperties.getProperty("url1");
            url2 = serverProperties.getProperty("url2");
            if(host == 1) url = url2;
            else url = url1;
            OkHttpClient client = new OkHttpClient.Builder().build();
            while(true) {
                try (ResponseBody body = client.newCall(
                        new Request.Builder().url(url + "/ping").build()).execute().body()) {
                    assert body != null;
                    String bodyString = body.string();
                    logger.info("Status: {}", bodyString.length() > 20 ? "Too long" : bodyString);
                    if(        bodyString.equals("\"ONLINE\"")
                            || bodyString.equals("\"WAITING\"")
                            || bodyString.equals("\"LOADING\"")
                            || bodyString.equals("\"BLOCKED\"")) {
                        String[] split = System.getenv("ADMINPASSWORD").split(" ");
                        int code = client.newCall(
                                new Request.Builder().url(url+"/stop")
                                .addHeader("Authorization", Credentials.basic(split[0], split[1]))
                                .build()
                        ).execute().code();
                        logger.info("Code: " + code);
                    } else break;
                }
                logger.info("WAIT");
                status = ServerStatus.WAITING;
                Thread.sleep(5000);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception ignored) {}
        logger.info("PASS");

    }

    public synchronized static void stop() {
        status = ServerStatus.BLOCKED;
        logger.warn("Stopping!");
        Main.class.notify();
    }
}
