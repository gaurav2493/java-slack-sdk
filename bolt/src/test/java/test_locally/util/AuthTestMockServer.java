package test_locally.util;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AuthTestMockServer {

    public static final String ValidToken = "xoxb-this-is-valid";

    static String ok = "{\n" +
            "  \"ok\": true,\n" +
            "  \"url\": \"https://java-slack-sdk-test.slack.com/\",\n" +
            "  \"team\": \"java-slack-sdk-test\",\n" +
            "  \"user\": \"test_user\",\n" +
            "  \"team_id\": \"T1234567\",\n" +
            "  \"user_id\": \"U1234567\",\n" +
            "  \"bot_id\": \"B12345678\",\n" +
            "  \"enterprise_id\": \"E12345678\"\n" +
            "}";
    static String ng = "{\n" +
            "  \"ok\": false,\n" +
            "  \"error\": \"invalid\"\n" +
            "}";

    @WebServlet
    public static class AuthTestMockEndpoint extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(200);
            resp.setContentType("application/json");
            if (req.getHeader("Authorization") == null || !req.getHeader("Authorization").equals("Bearer " + ValidToken)) {
                resp.getWriter().write(ng);
            } else {
                resp.getWriter().write(ok);
            }
        }
    }

    private final int port;
    private final Server server;

    public AuthTestMockServer() {
        this(PortProvider.getPort(AuthTestMockServer.class.getName()));
    }

    public AuthTestMockServer(int port) {
        this.port = port;
        server = new Server(this.port);
        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);
        handler.addServletWithMapping(AuthTestMockEndpoint.class, "/*");
    }

    public String getMethodsEndpointPrefix() {
        return "http://localhost:" + port + "/api/";
    }

    public void start() throws Exception {
        server.start();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public static class PortProvider {

        private PortProvider() {
        }

        private static final SecureRandom RANDOM = new SecureRandom();
        private static final ConcurrentMap<String, Integer> PORTS = new ConcurrentHashMap<>();

        public static int getPort(String name) {
            return PORTS.computeIfAbsent(name, (key) -> randomPort());
        }

        private static int randomPort() {
            while (true) {
                int randomPort = RANDOM.nextInt(9999);
                if (randomPort < 1000) {
                    randomPort += 1000;
                }
                if (isAvailable(randomPort)) {
                    return randomPort;
                }
            }
        }

        private static boolean isAvailable(int port) {
            try (Socket ignored = new Socket("localhost", port)) {
                return false;
            } catch (IOException ignored) {
                return true;
            }
        }
    }

}
