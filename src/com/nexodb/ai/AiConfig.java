package com.nexodb.ai;

import java.net.URI;
import java.util.*;

public record AiConfig(Provider provider,URI endpoint,String model) {
    public enum Provider {OPENAI("OpenAI","https://api.openai.com/v1/responses"),COMPATIBLE("API compatible","https://tu-proveedor.example/v1/chat/completions"),OLLAMA("Ollama local","http://localhost:11434/v1/chat/completions");
        private final String label,url;Provider(String label,String url){this.label=label;this.url=url;}public String defaultEndpoint(){return url;}@Override public String toString(){return label;}
    }
    public AiConfig {
        Objects.requireNonNull(provider);Objects.requireNonNull(endpoint);model=Objects.requireNonNull(model).trim();
        String host=endpoint.getHost();boolean local=host!=null&&Set.of("localhost","127.0.0.1","[::1]","::1").contains(host.toLowerCase(Locale.ROOT));
        if(host==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!("https".equalsIgnoreCase(endpoint.getScheme())||local&&"http".equalsIgnoreCase(endpoint.getScheme())))throw new IllegalArgumentException("Usa un endpoint HTTPS; HTTP solo se admite en el equipo local. La URL no debe incluir credenciales ni parámetros.");
        if(model.isBlank()||model.length()>200)throw new IllegalArgumentException("Indica el nombre del modelo habilitado en tu proveedor.");
        if(provider==Provider.OPENAI&&!("api.openai.com".equalsIgnoreCase(host)&&endpoint.getPath().equals("/v1/responses")))throw new IllegalArgumentException("Para otro servidor utiliza API compatible.");
    }
    public String secretId(){return "ai:"+provider.name()+":"+endpoint.normalize();}
}
