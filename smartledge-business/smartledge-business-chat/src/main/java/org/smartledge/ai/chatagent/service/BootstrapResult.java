package org.smartledge.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;

/**
 * @description: 服务层
 * @author: Song
 **/

@Data
@AllArgsConstructor
public class BootstrapResult {

    private final Flux<String> outbound;

    private final String rejectionMessage;

    public static BootstrapResult ready(Flux<String> outbound) {
        return new BootstrapResult(outbound, "");
    }

    public static BootstrapResult rejected(String rejectionMessage) {
        return new BootstrapResult(Flux.empty(), rejectionMessage);
    }
}
