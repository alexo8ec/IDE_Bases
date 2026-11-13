import javax.net.ssl.HttpsURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;

/** Run with the packaged runtime to catch missing cryptographic modules. */
public final class RuntimeTlsCheck {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator.getInstance("EC").generateKeyPair();
        if (args.length != 0) {
            HttpsURLConnection connection=(HttpsURLConnection)URI.create(args[0]).toURL().openConnection();
            connection.setConnectTimeout(20000);connection.setReadTimeout(20000);
            try {
                if(connection.getResponseCode()!=200)throw new AssertionError("HTTPS status: "+connection.getResponseCode());
                try(var input=connection.getInputStream()) {
                    String json=new String(input.readNBytes(1048576),StandardCharsets.UTF_8);
                    if(!json.contains("\"version\""))throw new AssertionError("Not an update manifest");
                }
            }finally{connection.disconnect();}
            System.out.println("Packaged runtime: EC provider and direct Java HTTPS verified.");
        } else System.out.println("Packaged runtime: EC provider verified.");
    }
}
