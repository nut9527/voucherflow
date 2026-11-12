package com.example.vfsupplier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** 绑定供应商访问凭证、回调签名密钥和回调地址。 */
@Component
@ConfigurationProperties("supplier")
public class SupplierProperties {
    private String apiToken="";
    private String callbackSecret="";
    private String callbackUrl="http://127.0.0.1:8080/api/supplier/callbacks";
    public String getApiToken() { return apiToken; }
    public void setApiToken(String v) { apiToken=v; }
    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String v) { callbackSecret=v; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { callbackUrl=v; }
    @jakarta.annotation.PostConstruct void validate() {
        if(apiToken.length()<24 || callbackSecret.length()<24) throw new IllegalArgumentException("Supplier secrets must contain at least 24 characters");
    }
}
