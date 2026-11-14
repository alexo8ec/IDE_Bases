package com.nexodb.ai;

import com.nexodb.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class AiClientTest {
    public static void main(String[] args)throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<Map<String,Object>> sent=new AtomicReference<>();AtomicReference<String> auth=new AtomicReference<>();AtomicInteger redirected=new AtomicInteger();
        server.createContext("/chat",exchange->{try{sent.set(Json.object(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] response=Json.stringify(Map.of("choices",List.of(Map.of("message",Map.of("content","```sql\nSELECT 'mañana' AS name;\n```\nPropuesta para revisar."))))).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/leak");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/leak",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        server.createContext("/error",exchange->{byte[] response="secret-test-key".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,response.length);exchange.getResponseBody().write(response);exchange.close();});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();var config=new AiConfig(AiConfig.Provider.COMPATIBLE,URI.create(base+"/chat"),"test-model");
            AiClient.Answer answer=new AiClient().request(config,"secret-test-key".toCharArray(),AiClient.Task.OPTIMIZE_QUERY,"Conserva NULL","SELECT 1","Motor: H2; tabla \"mañana\"","");
            equal("SELECT 'mañana' AS name;",answer.sql());equal("Bearer secret-test-key",auth.get());equal("test-model",sent.get().get("model"));
            if(!Json.stringify(sent.get()).contains("Conserva NULL"))throw new AssertionError("Request lost Unicode/context");
            if(sent.get().containsKey("tools"))throw new AssertionError("AI must not have execution tools");
            new AiClient().request(new AiConfig(AiConfig.Provider.OLLAMA,URI.create(base+"/chat"),"local-test"),new char[0],AiClient.Task.GENERATE,"Generate a SELECT","","H2","");equal(null,auth.get());
            var openai=new AiConfig(AiConfig.Provider.OPENAI,URI.create(AiConfig.Provider.OPENAI.defaultEndpoint()),"user-selected-model");var body=AiClient.requestBody(openai,AiClient.Task.OPTIMIZE_PROCEDURE,"Preserve parameters","CREATE PROCEDURE x AS SELECT 1","SQL Server","");equal(false,body.get("store"));equal("user-selected-model",body.get("model"));if(body.containsKey("tools"))throw new AssertionError("Unexpected tools");
            String text=AiClient.extract(AiConfig.Provider.OPENAI,Map.of("output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","SQL answer"))))));equal("SQL answer",text);
            for(String path:List.of("/redirect","/error"))try{new AiClient().request(new AiConfig(AiConfig.Provider.COMPATIBLE,URI.create(base+path),"model"),"secret-test-key".toCharArray(),AiClient.Task.GENERATE,"SQL","","","");throw new AssertionError("Expected HTTP error");}catch(java.io.IOException error){if(error.getMessage().contains("secret-test-key"))throw new AssertionError("Error leaked credential");}
            equal(0,redirected.get());
            for(String url:List.of("http://external.example/chat","https://user:password@example.com/chat","https://example.com/chat?key=secret"))try{new AiConfig(AiConfig.Provider.COMPATIBLE,URI.create(url),"model");throw new AssertionError("Unsafe endpoint accepted");}catch(IllegalArgumentException expected){}
            try{AiClient.prompt(AiClient.Task.GENERATE,"x".repeat(200001),"","","");throw new AssertionError("Unbounded context accepted");}catch(IllegalArgumentException expected){}
        }finally{server.stop(0);}
        System.out.println("AiClientTest: OK (local HTTP contract, Unicode, SQL extraction, Responses/store=false, selected model, no tools, endpoint validation, no redirects or credential disclosure)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
