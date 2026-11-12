package com.example.vfsupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
/** 保存一次性响应丢失与持续暂停回调的演示状态。 */
@Component
public class DemoFaults {
    private final AtomicBoolean dropResponse=new AtomicBoolean();
    private final AtomicBoolean callbacksPaused=new AtomicBoolean();
    void set(boolean drop,boolean paused) { dropResponse.set(drop);callbacksPaused.set(paused); }
    public boolean callbacksPaused() { return callbacksPaused.get(); }
    /** 业务结果已提交后仅让下一次响应失败，用于验证调用方能通过查单恢复。 */
    public void afterCommit() {
        if(dropResponse.compareAndSet(true,false)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Injected response loss after commit");
    }
}
