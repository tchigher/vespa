// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.ClusterInfo;
import com.yahoo.vespa.hosted.controller.application.ClusterUtilization;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.metric.ApplicationMetrics;
import com.yahoo.vespa.hosted.controller.rotation.RotationStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * An application that has been locked for modification. Provides methods for modifying an application's fields.
 *
 * @author jonmv
 */
public class LockedApplication {

    private final Lock lock;
    private final ApplicationId id;
    private final Instant createdAt;
    private final DeploymentSpec deploymentSpec;
    private final ValidationOverrides validationOverrides;
    private final Map<ZoneId, Deployment> deployments;
    private final DeploymentJobs deploymentJobs;
    private final Change change;
    private final Change outstandingChange;
    private final Optional<IssueId> ownershipIssueId;
    private final Optional<User> owner;
    private final OptionalInt majorVersion;
    private final ApplicationMetrics metrics;
    private final Optional<String> pemDeployKey;
    private final List<AssignedRotation> rotations;
    private final RotationStatus rotationStatus;

    /**
     * Used to create a locked application
     *
     * @param application The application to lock.
     * @param lock The lock for the application.
     */
    LockedApplication(Application application, Lock lock) {
        this(Objects.requireNonNull(lock, "lock cannot be null"), application.id(), application.createdAt(),
             application.deploymentSpec(), application.validationOverrides(),
             application.deployments(),
             application.deploymentJobs(), application.change(), application.outstandingChange(),
             application.ownershipIssueId(), application.owner(), application.majorVersion(), application.metrics(),
             application.pemDeployKey(), application.rotations(), application.rotationStatus());
    }

    private LockedApplication(Lock lock, ApplicationId id, Instant createdAt,
                              DeploymentSpec deploymentSpec, ValidationOverrides validationOverrides,
                              Map<ZoneId, Deployment> deployments, DeploymentJobs deploymentJobs, Change change,
                              Change outstandingChange, Optional<IssueId> ownershipIssueId, Optional<User> owner,
                              OptionalInt majorVersion, ApplicationMetrics metrics, Optional<String> pemDeployKey,
                              List<AssignedRotation> rotations, RotationStatus rotationStatus) {
        this.lock = lock;
        this.id = id;
        this.createdAt = createdAt;
        this.deploymentSpec = deploymentSpec;
        this.validationOverrides = validationOverrides;
        this.deployments = deployments;
        this.deploymentJobs = deploymentJobs;
        this.change = change;
        this.outstandingChange = outstandingChange;
        this.ownershipIssueId = ownershipIssueId;
        this.owner = owner;
        this.majorVersion = majorVersion;
        this.metrics = metrics;
        this.pemDeployKey = pemDeployKey;
        this.rotations = rotations;
        this.rotationStatus = rotationStatus;
    }

    /** Returns a read-only copy of this */
    public Application get() {
        return new Application(id, createdAt, deploymentSpec, validationOverrides, deployments, deploymentJobs, change,
                            outstandingChange, ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                            rotations, rotationStatus);
    }

    public LockedApplication withBuiltInternally(boolean builtInternally) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs.withBuiltInternally(builtInternally), change, outstandingChange,
                                     ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                                     rotations, rotationStatus);
    }

    public LockedApplication withProjectId(OptionalLong projectId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs.withProjectId(projectId), change, outstandingChange,
                                     ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                                     rotations, rotationStatus);
    }

    public LockedApplication withDeploymentIssueId(IssueId issueId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs.with(issueId), change, outstandingChange,
                                     ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                                     rotations, rotationStatus);
    }

    public LockedApplication withJobCompletion(long projectId, JobType jobType) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs.withProjectId(jobType == JobType.component ? OptionalLong.of(projectId)
                                                                                               : deploymentJobs.projectId()),
                                     change, outstandingChange, ownershipIssueId, owner, majorVersion, metrics,
                                     pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication with(DeploymentSpec deploymentSpec) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange,
                                     ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                                     rotations, rotationStatus);
    }

    public LockedApplication with(ValidationOverrides validationOverrides) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                                     metrics, pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication withChange(Change change) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                                     metrics, pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication withOutstandingChange(Change outstandingChange) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                                     metrics, pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication withOwnershipIssueId(IssueId issueId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, Optional.ofNullable(issueId), owner,
                                     majorVersion, metrics, pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication withOwner(User owner) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId,
                                     Optional.ofNullable(owner), majorVersion, metrics, pemDeployKey,
                                     rotations, rotationStatus);
    }

    /** Set a major version for this, or set to null to remove any major version override */
    public LockedApplication withMajorVersion(Integer majorVersion) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId, owner,
                                     majorVersion == null ? OptionalInt.empty() : OptionalInt.of(majorVersion),
                                     metrics, pemDeployKey, rotations, rotationStatus);
    }

    public LockedApplication with(ApplicationMetrics metrics) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides, deployments,
                                     deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                                     metrics, pemDeployKey, rotations, rotationStatus);
    }

    @Override
    public String toString() {
        return "application '" + id + "'";
    }

}