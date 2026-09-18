package com.secureleaf.creator.entity;

import com.secureleaf.auth.entity.User;
import com.secureleaf.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "creator_profiles")
@Getter
@Setter
public class CreatorProfile extends BaseEntity {

    /**
     * PK is also the FK to users — this is a 1-to-1 extension table.
     * We use @MapsId so the id value is shared with the User.
     */
    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "payout_email")
    private String payoutEmail;

    @Column(name = "payout_upi", length = 100)
    private String payoutUpi;

}
