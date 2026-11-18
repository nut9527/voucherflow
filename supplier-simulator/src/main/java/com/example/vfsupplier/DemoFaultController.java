package com.example.vfsupplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 提供演示故障开关，仅在显式启用故障接口时注册。 */
@RestController
@ConditionalOnProperty(name = "supplier.faults-enabled", havingValue = "true")
public class DemoFaultController {
    private final DemoFaults faults;

    public DemoFaultController(DemoFaults faults) {
        this.faults = faults;
    }

    public record Settings(boolean dropNextResponse, boolean pauseCallbacks) {}

    @PutMapping("/supplier/faults")
    public Settings configure(@RequestBody Settings settings) {
        faults.set(settings.dropNextResponse(), settings.pauseCallbacks());
        return settings;
    }
}
