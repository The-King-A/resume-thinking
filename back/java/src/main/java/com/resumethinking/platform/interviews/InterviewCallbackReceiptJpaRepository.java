package com.resumethinking.platform.interviews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "interview_callback_receipts")
class InterviewCallbackReceiptEntity {
    @Id
    @Column(name = "callback_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String callbackId;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    protected InterviewCallbackReceiptEntity() { }
    InterviewCallbackReceiptEntity(InterviewCallbackReceipt value) { callbackId = value.callbackId(); payloadHash = value.payloadHash(); receivedAt = value.receivedAt(); }
    InterviewCallbackReceipt record() { return new InterviewCallbackReceipt(callbackId, payloadHash, receivedAt); }
}

interface InterviewCallbackReceiptSpringRepository extends JpaRepository<InterviewCallbackReceiptEntity, String> { }

@Repository
public class InterviewCallbackReceiptJpaRepository implements InterviewCallbackReceiptRepository {
    private final InterviewCallbackReceiptSpringRepository delegate;
    public InterviewCallbackReceiptJpaRepository(InterviewCallbackReceiptSpringRepository delegate) { this.delegate = delegate; }
    public InterviewCallbackReceipt save(InterviewCallbackReceipt value) { return delegate.save(new InterviewCallbackReceiptEntity(value)).record(); }
    public Optional<InterviewCallbackReceipt> findByCallbackId(String id) { return delegate.findById(id).map(InterviewCallbackReceiptEntity::record); }
}
