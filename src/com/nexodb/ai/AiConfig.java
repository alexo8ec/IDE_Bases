package com.nexodb.ai;

import java.net.URI;
import java.util.*;

public record AiConfig(Provider provider,URI endpoint,String model) {
    public enum Provider {VICKY("Vicky","http://100.106.113.53:43110/v1/database-agent/chat","vicky-core"),OPENAI("OpenAI","https://api.openai.com/v1/responses",""),COMPATIBLE("API compatible","https://tu-proveedor.example/v1/chat/completions",""),OLLAMA("Ollama local","http://localhost:11434/v1/chat/completions","");
        private final String label,url,model;Provider(String label,String url,String model){this.label=label;this.url=url;this.model=model;}public String defaultEndpoint(){return url;}public String defaultModel(){return model;}@Override public String toString(){return label;}
    }
    public AiConfig {
        Objects.requireNonNull(provider);Objects.requireNonNull(endpoint);model=Objects.requireNonNull(model).trim();
        String host=endpoint.getHost();boolean local=host!=null&&Set.of("localhost","127.0.0.1","[::1]","::1").contains(host.toLowerCase(Locale.ROOT));
        boolean tailscale=provider==Provider.VICKY&&isTailscaleIpv4(host);
        if(host==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!("https".equalsIgnoreCase(endpoint.getScheme())||(local||tailscale)&&"http".equalsIgnoreCase(endpoint.getScheme())))throw new IllegalArgumentException("Usa HTTPS; HTTP solo se admite en el equipo local o con Vicky dentro de Tailscale. La URL no debe incluir credenciales ni parámetros.");
        if(provider==Provider.VICKY&&!endpoint.getPath().equals("/v1/database-agent/chat"))throw new IllegalArgumentException("El endpoint de Vicky debe terminar en /v1/database-agent/chat.");
        if(provider==Provider.VICKY&&model.isBlank())model=provider.defaultModel();
        if(model.isBlank()||model.length()>200)throw new IllegalArgumentException("Indica el nombre del modelo habilitado en tu proveedor.");
        if(provider==Provider.OPENAI&&!("api.openai.com".equalsIgnoreCase(host)&&endpoint.getPath().equals("/v1/responses")))throw new IllegalArgumentException("Para otro servidor utiliza API compatible.");
    }
    private static boolean isTailscaleIpv4(String host){if(host==null)return false;String[] parts=host.split("\\.");if(parts.length!=4)return false;try{int first=Integer.parseInt(parts[0]),second=Integer.parseInt(parts[1]);for(String part:parts){int value=Integer.parseInt(part);if(value<0||value>255)return false;}return first==100&&second>=64&&second<=127;}catch(NumberFormatException error){return false;}}
    public String secretId(){return "ai:"+provider.name()+":"+endpoint.normalize();}
}
