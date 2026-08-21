package com.lrj.benefit.web;

import com.lrj.benefit.adapters.channel.ChannelCallbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/callbacks/v1/channels")
public class ChannelCallbackController {
    private final ChannelCallbackService callbacks;
    public ChannelCallbackController(ChannelCallbackService callbacks) { this.callbacks = callbacks; }

    @PostMapping("/{channelCode}")
    public ResponseEntity<ChannelCallbackService.CallbackReceipt> receive(
            @PathVariable String channelCode,
            @RequestHeader("X-Callback-Timestamp") String timestamp,
            @RequestHeader("X-Callback-Nonce") String nonce,
            @RequestHeader("X-Callback-Signature") String signature,
            @RequestBody String body) {
        return ResponseEntity.accepted().body(callbacks.receive(channelCode, timestamp, nonce, signature, body));
    }
}
