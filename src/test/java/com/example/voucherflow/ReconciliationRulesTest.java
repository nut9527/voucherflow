package com.example.voucherflow;

import com.example.voucherflow.reconciliation.*;
import com.example.voucherflow.reconciliation.DifferenceClassifier.Observation;
import com.example.voucherflow.reconciliation.ReconciliationDtos.RepairRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证差异分类、修复授权与命令指纹所依据的状态和证据规则。 */
class ReconciliationRulesTest {
    private final DifferenceClassifier classifier=new DifferenceClassifier();
    private Observation order(String status,long revision) {
        return new Observation(1,2,revision,status,"SUPPLIER","PAUSED",0,Instant.now().plusSeconds(3600),null);
    }
    @Test void unknownDoesNotAuthorizeRelease() {
        var order=order("UNKNOWN",7);
        for(var remote:new String[]{"NOT_FOUND","ACCEPTED","UNAVAILABLE"}) {
            var result=classifier.classify(order,remote,order,true);
            assertThat(result.type()).isEqualTo("AWAITING_CONFIRMATION");
            assertThat(result.operations()).containsExactly(RepairOperation.REQUERY);
        }
    }
    @Test void changedRevisionRequiresRecheck() {
        assertThat(classifier.classify(order("UNKNOWN",7),"SUCCEEDED",order("FULFILLED",8),true).state()).isEqualTo("NEEDS_RECHECK");
    }
    @Test void terminalOrderWithoutRemoteConfirmationStillNeedsRecheck() {
        for(var remote:new String[]{"NOT_FOUND","ACCEPTED","UNAVAILABLE"}) {
            var order=order("FULFILLED",8);
            var result=classifier.classify(order,remote,order,true);
            assertThat(result.state()).isEqualTo("NEEDS_RECHECK");
            assertThat(result.operations()).isEmpty();
        }
    }
    @Test void contradictoryTerminalIsReviewOnly() {
        var order=order("FAILED",8);
        var result=classifier.classify(order,"SUCCEEDED",order,true);
        assertThat(result.type()).isEqualTo("TERMINAL_CONFLICT");
        assertThat(result.operations()).isEmpty();
    }
    @Test void brokenAccountingCannotBeAutomaticallyRepaired() {
        var order=order("UNKNOWN",7);
        assertThat(classifier.classify(order,"SUCCEEDED",order,false).operations()).isEmpty();
    }
    @Test void onlyExplicitRemoteTerminalsPermitInventoryChanges() {
        var order=order("UNKNOWN",7);
        assertThat(classifier.classify(order,"SUCCEEDED",order,true).operations()).containsExactly(RepairOperation.CONFIRM);
        assertThat(classifier.classify(order,"REJECTED",order,true).operations()).containsExactly(RepairOperation.RELEASE);
    }
    @Test void commandFingerprintIncludesReasonOperatorRevisionAndTarget() {
        var fingerprints=new CommandFingerprint(JsonMapper.builder().build());var id=UUID.randomUUID();
        var request=new RepairRequest(id,7,RepairOperation.CONFIRM,"supplier query verified");
        var hash=fingerprints.calculate(1,2,null,3,request);
        assertThat(fingerprints.calculate(1,2,null,3,request)).isEqualTo(hash);
        assertThat(fingerprints.calculate(1,2,null,4,request)).isNotEqualTo(hash);
        assertThat(fingerprints.calculate(1,2,"event",3,request)).isNotEqualTo(hash);
        assertThat(fingerprints.calculate(1,2,null,3,new RepairRequest(id,8,request.operation(),request.reason()))).isNotEqualTo(hash);
        assertThat(fingerprints.calculate(1,2,null,3,new RepairRequest(id,7,request.operation(),"different reason"))).isNotEqualTo(hash);
    }
}
