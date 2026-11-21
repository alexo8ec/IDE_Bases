package com.nexodb.db;

import com.nexodb.util.SecretStore;
import java.util.Objects;

/** Per-task credentials encrypted by the operating-system account, separate from connection profiles. */
public final class ScheduledTaskSecrets {
    private static final String PREFIX="scheduled-task:";
    private final SecretStore store;
    public ScheduledTaskSecrets(SecretStore store){this.store=Objects.requireNonNull(store);}
    public static String secretId(String taskId){String value=Objects.toString(taskId,"").trim();if(value.isBlank())throw new IllegalArgumentException("La tarea no tiene identificador.");return PREFIX+value;}
    public boolean supported(){return store.supported();}
    public boolean has(String taskId){return store.has(secretId(taskId));}
    public char[] load(String taskId)throws Exception{return store.load(secretId(taskId));}
    public void save(String taskId,char[] password)throws Exception{store.save(secretId(taskId),password);}
    public void delete(String taskId){store.delete(secretId(taskId));}
}
