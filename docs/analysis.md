**Findings**

1. **High: relays ACK and deliver unicast packets that are not addressed to this node.**  
   Router.cpp delivers every decryptable packet to BLE and generates `AIRTAK_ACK` for any packet with `wantAck()`, without checking `hdr.dest_node_id` or `data_meta.dest` against `MeshNode::nodeId()` or broadcast. In a multi-hop unicast, an intermediate relay can ACK a message before the intended destination receives it, causing false delivery success and breaking Phase 2.11’s ACK semantics.

2. **High: final ACK retry is marked failed immediately after being sent.**  
   Router.cpp checks `e.attempts >= ACK_MAX_RETRIES` before considering the timeout window. After the third retry is transmitted, `e.attempts` becomes `3`; the next retry tick reports failure immediately, without waiting for the ACK timeout for that final attempt. This weakens the promised “up to 3 retries” behavior and can falsely fail slow multi-hop ACK returns.

3. **High: GPS task can starve lower-priority firmware work.**  
   main.cpp runs `GPS::process()` in a tight infinite loop with no delay, queue wait, or yield. Since the GPS task priority is `3`, it can starve lower-priority config apply, display refresh, and battery tasks, and can compete heavily with BLE at the same priority. This is especially risky because hot-reload radio restarts run in the low-priority config task.

4. **High: radio restart can reinitialize while the radio task is not actually paused.**  
   ConfigManager.cpp waits only 100 ms for `Radio::isPaused()` and then proceeds to drain queues and call `Radio::reinit()` even if the radio task never acknowledged pause. Profile 0 packets can easily exceed 100 ms airtime, so a config apply during TX can race with `Radio::transmit()`. The admin restart path is even less safe: AirTAKService.cpp calls `pause()`, `reinit()`, and `resume()` back-to-back, skipping the controlled restart procedure and `ChannelManager::rekey()`.

5. **Medium: native tests miss the most important protocol ownership cases.**  
   test_router.cpp builds only broadcast OTA packets for receive-path tests, while NativeStubs.cpp stubs channel/config/radio behavior heavily. That leaves the unicast relay/ACK bug above untested, and it also means config parsing plus real `ChannelManager::rekey()` behavior is not exercised by the router suite.

**Notes**

The core shape is promising: hop defaults match v1.5, `hop_start` is preserved on relay, audio relay is blocked, position requests are implemented, and the config restart path is at least structurally present. The biggest concern is that the ACK layer currently treats “I can decrypt this” as “I am the receiver,” which undermines the reliability design on real meshes.

A focused next step would be to fix destination ownership in `Router::processIncoming()`, adjust retry failure timing, add a scheduler yield/block to the GPS task, and add native tests for unicast relay behavior where an intermediate node must relay but must not deliver or ACK.