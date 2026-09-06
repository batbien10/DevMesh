package devmesh.config;

import java.util.Map;

/**
 */
public class HookConfig {

    private String id;
    private String event;
    private String condition;
    private String type;
    private String command;
    private String message;
    private boolean reject;



    private boolean once;

    private boolean async;

    /**
     */
    private String onError;

    private String url;

    private String method;

    private Map<String, String> headers;

    private String body;

    private int timeout;

    // ---- getters & setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isReject() { return reject; }
    public void setReject(boolean reject) { this.reject = reject; }

    public boolean isOnce() { return once; }
    public void setOnce(boolean once) { this.once = once; }

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }

    public String getOnError() { return onError; }
    public void setOnError(String onError) { this.onError = onError; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
}
