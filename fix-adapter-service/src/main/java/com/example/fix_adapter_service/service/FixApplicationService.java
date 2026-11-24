package com.example.fix_adapter_service.service;

import com.example.fix_adapter_service.model.MessageKind;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import quickfix.*;

@Service
@RequiredArgsConstructor
public class FixApplicationService implements Application {
    private static final Logger log=LoggerFactory.getLogger(FixApplicationService.class);
    private final FixMessageCodec codec;
    private final InboundQueueService inbound;
    private final ReceiptService receipts;
    @Override public void fromApp(Message message,SessionID sessionId) throws FieldNotFound,IncorrectDataFormat,IncorrectTagValue,UnsupportedMessageType {
        try {
            var decoded=codec.decode(message);
            if(decoded.kind()==MessageKind.RECEIPT)receipts.acknowledge(decoded.eventId(),decoded.payloadHash(),sessionId.toString());
            else {
                // receive() is a separate Spring transaction. It has committed before sending U3.
                var span = GlobalOpenTelemetry.getTracer("lending.fix").spanBuilder("FIX receive")
                    .setAttribute("lending.event_id", decoded.eventId()).startSpan();
                try (var scope = span.makeCurrent()) {
                    inbound.receive(decoded,sessionId.toString());
                    log.atDebug().addKeyValue("event_id", decoded.eventId()).log("Received FIX event");
                    if (!Session.sendToTarget(codec.receipt(decoded.eventId(),decoded.payloadHash()),sessionId)) {
                        span.setStatus(StatusCode.ERROR);
                        log.warn("Receipt send failed for {}; the peer will retry the same event",decoded.eventId());
                    }
                } catch (Exception e) {
                    span.setStatus(StatusCode.ERROR);
                    throw e;
                } finally {
                    span.end();
                }
            }
        } catch(FieldNotFound e){throw e;}
        catch(IllegalArgumentException e) {log.warn("Rejected FIX application message on {}: {}",sessionId,e.getMessage());throw new IncorrectTagValue(FixMessageCodec.EVENT_ID);}
        catch(Exception e) {
            // No receipt on persistence failure. Retrying from the peer closes the sequence/DB crash window.
            log.error("FIX inbound persistence/receipt failed on {}",sessionId,e);throw new IncorrectDataFormat(FixMessageCodec.PAYLOAD);
        }
    }
    @Override public void onCreate(SessionID id){log.info("FIX session created: {}",id);}
    @Override public void onLogon(SessionID id){log.info("FIX session logged on: {}",id);}
    @Override public void onLogout(SessionID id){log.info("FIX session logged out: {}",id);}
    @Override public void toAdmin(Message m,SessionID id){}
    @Override public void fromAdmin(Message m,SessionID id){}
    @Override public void toApp(Message m,SessionID id){}
}
