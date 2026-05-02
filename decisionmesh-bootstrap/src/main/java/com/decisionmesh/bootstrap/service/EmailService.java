package com.decisionmesh.bootstrap.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
public class EmailService {

    @Inject
    ReactiveMailer mailer;

    public Uni<Void> sendInviteEmail(String email, String link) {
        return mailer.send(
                Mail.withText(
                        email,
                        "You're invited to DecisionMesh",
                        "Click to join: " + link
                )
        );
    }
}