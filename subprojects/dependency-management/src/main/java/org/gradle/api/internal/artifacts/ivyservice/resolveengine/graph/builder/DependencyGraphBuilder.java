/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.internal.artifacts.ComponentSelectorConverter;
import org.gradle.api.internal.artifacts.ResolveContext;
import org.gradle.api.internal.artifacts.ResolvedVersionConstraint;
import org.gradle.api.internal.artifacts.dsl.ModuleReplacementsData;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.ConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.PotentialConflict;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.specs.Spec;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.resolver.ResolveContextToComponentResolver;
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DependencyGraphBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyGraphBuilder.class);
    private static final Predicate<SelectorState> ALL_SELECTORS = Predicates.alwaysTrue();
    private final ConflictHandler conflictHandler;
    private final Spec<? super DependencyMetadata> edgeFilter;
    private final ResolveContextToComponentResolver moduleResolver;
    private final DependencyToComponentIdResolver idResolver;
    private final ComponentMetaDataResolver metaDataResolver;
    private final AttributesSchemaInternal attributesSchema;
    private final ModuleExclusions moduleExclusions;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ModuleReplacementsData moduleReplacementsData;
    private final ComponentSelectorConverter componentSelectorConverter;
    private final DependencySubstitutionApplicator dependencySubstitutionApplicator;
    private final ImmutableAttributesFactory attributesFactory;

    public DependencyGraphBuilder(DependencyToComponentIdResolver componentIdResolver, ComponentMetaDataResolver componentMetaDataResolver,
                                  ResolveContextToComponentResolver resolveContextToComponentResolver,
                                  ConflictHandler conflictHandler, Spec<? super DependencyMetadata> edgeFilter,
                                  AttributesSchemaInternal attributesSchema,
                                  ModuleExclusions moduleExclusions,
                                  BuildOperationExecutor buildOperationExecutor, ModuleReplacementsData moduleReplacementsData,
                                  DependencySubstitutionApplicator dependencySubstitutionApplicator, ComponentSelectorConverter componentSelectorConverter,
                                  ImmutableAttributesFactory attributesFactory) {
        this.idResolver = componentIdResolver;
        this.metaDataResolver = componentMetaDataResolver;
        this.moduleResolver = resolveContextToComponentResolver;
        this.conflictHandler = conflictHandler;
        this.edgeFilter = edgeFilter;
        this.attributesSchema = attributesSchema;
        this.moduleExclusions = moduleExclusions;
        this.buildOperationExecutor = buildOperationExecutor;
        this.moduleReplacementsData = moduleReplacementsData;
        this.dependencySubstitutionApplicator = dependencySubstitutionApplicator;
        this.componentSelectorConverter = componentSelectorConverter;
        this.attributesFactory = attributesFactory;
    }

    public void resolve(final ResolveContext resolveContext, final DependencyGraphVisitor modelVisitor) {

        IdGenerator<Long> idGenerator = new LongIdGenerator();
        DefaultBuildableComponentResolveResult rootModule = new DefaultBuildableComponentResolveResult();
        moduleResolver.resolve(resolveContext, rootModule);

        final ResolveState resolveState = new ResolveState(idGenerator, rootModule, resolveContext.getName(), idResolver, metaDataResolver, edgeFilter, attributesSchema, moduleExclusions, moduleReplacementsData, componentSelectorConverter, attributesFactory, dependencySubstitutionApplicator);
        conflictHandler.registerResolver(new DirectDependencyForcingResolver(resolveState.getRoot().getComponent()));

        traverseGraph(resolveState);

        resolveState.getRoot().getComponent().setRoot();

        assembleResult(resolveState, modelVisitor);

    }

    /**
     * Traverses the dependency graph, resolving conflicts and building the paths from the root configuration.
     */
    private void traverseGraph(final ResolveState resolveState) {
        resolveState.onMoreSelected(resolveState.getRoot());
        final List<EdgeState> dependencies = Lists.newArrayList();
        final Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache = Maps.newHashMap();

        final PendingDependenciesHandler pendingDependenciesHandler = new DefaultPendingDependenciesHandler();

        while (resolveState.peek() != null || conflictHandler.hasConflicts()) {
            if (resolveState.peek() != null) {
                final NodeState node = resolveState.pop();
                LOGGER.debug("Visiting configuration {}.", node);

                // Initialize and collect any new outgoing edges of this node
                dependencies.clear();
                node.visitOutgoingDependencies(dependencies, pendingDependenciesHandler);
                resolveEdges(node, dependencies, resolveState, componentIdentifierCache);
            } else {
                // We have some batched up conflicts. Resolve the first, and continue traversing the graph
                conflictHandler.resolveNextConflict(resolveState.getReplaceSelectionWithConflictResultAction());
            }

        }
    }

    private void performSelection(final ResolveState resolveState, ComponentState moduleRevision) {
        ModuleIdentifier moduleId = moduleRevision.getId().getModule();

        ModuleResolveState module = resolveState.getModule(moduleId);
        // Check for a new conflict
        if (moduleRevision.isSelectable()) {

            if (tryCompatibleSelection(resolveState, moduleRevision, moduleId, module)) {
                return;
            }

            // A new module revision. Check for conflict
            PotentialConflict c = conflictHandler.registerModule(module);
            if (!c.conflictExists()) {
                // No conflict. Select it for now
                LOGGER.debug("Selecting new module version {}", moduleRevision);
                module.select(moduleRevision);
            } else {
                // We have a conflict
                LOGGER.debug("Found new conflicting module version {}", moduleRevision);

                // Deselect the currently selected version, and remove all outgoing edges from the version
                // This will propagate through the graph and prune configurations that are no longer required
                // For each module participating in the conflict (many times there is only one participating module that has multiple versions)
                c.withParticipatingModules(resolveState.getDeselectVersionAction());
            }
        }
    }

    private static boolean tryCompatibleSelection(final ResolveState resolveState,
                                                  final ComponentState candidate,
                                                  final ModuleIdentifier moduleId,
                                                  final ModuleResolveState module) {
        final ComponentState currentlySelected = module.getSelected();
        String version = candidate.getId().getVersion();
        List<SelectorState> moduleSelectors = module.getSelectors();
        if (currentlySelected == null && !resolveState.getModuleReplacementsData().participatesInReplacements(moduleId)) {
            if (allSelectorsAgreeWith(moduleSelectors, version, ALL_SELECTORS)) {
                module.select(candidate);
                return true;
            }
        }

        final Collection<SelectorState> selectedBy = candidate.allResolvers;
        if (currentlySelected != null && currentlySelected != candidate) {
            if (allSelectorsAgreeWith(candidate.allResolvers, currentlySelected.getVersion(), ALL_SELECTORS)) {
                // if this selector agrees with the already selected version, don't bother and pick it
                return true;
            }

            if (allSelectorsAgreeWith(moduleSelectors, version, new Predicate<SelectorState>() {
                @Override
                public boolean apply(@Nullable SelectorState input) {
                    return !selectedBy.contains(input);
                }
            })) {
                resolveState.getDeselectVersionAction().execute(moduleId);
                module.softSelect(candidate);
                return true;
            }
        }
        // we're going to fallback to conflict resolution
        return false;
    }

    private void resolveEdges(final NodeState node,
                              final List<EdgeState> dependencies,
                              final ResolveState resolveState,
                              final Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache) {
        if (dependencies.isEmpty()) {
            return;
        }
        performSelectionSerially(dependencies, resolveState);
        maybeDownloadMetadataInParallel(node, componentIdentifierCache, dependencies);
        attachToTargetRevisionsSerially(dependencies);

    }

    private void performSelectionSerially(List<EdgeState> dependencies, ResolveState resolveState) {
        for (EdgeState dependency : dependencies) {
            ComponentState moduleRevision = dependency.resolveModuleRevisionId();
            if (moduleRevision != null) {
                performSelection(resolveState, moduleRevision);
            }
        }
    }

    /**
     * Prepares the resolution of edges, either serially or concurrently.
     * It uses a simple heuristic to determine if we should perform concurrent resolution, based on the the number of edges, and whether they have unresolved metadata.
     */
    private void maybeDownloadMetadataInParallel(NodeState node, Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache, List<EdgeState> dependencies) {
        List<EdgeState> requiringDownload = null;
        for (EdgeState dependency : dependencies) {
            ComponentState targetComponent = dependency.getTargetComponent();
            if (targetComponent != null && !targetComponent.fastResolve() && performPreemptiveDownload(targetComponent)) {
                if (!metaDataResolver.isFetchingMetadataCheap(toComponentId(targetComponent.getId(), componentIdentifierCache))) {
                    // Avoid initializing the list if there are no components requiring download (a common case)
                    if (requiringDownload == null) {
                        requiringDownload = Lists.newArrayList();
                    }
                    requiringDownload.add(dependency);
                }
            }
        }
        // Only download in parallel if there is more than 1 component to download
        if (requiringDownload != null && requiringDownload.size() > 1) {
            final ImmutableList<EdgeState> toDownloadInParallel = ImmutableList.copyOf(requiringDownload);
            LOGGER.debug("Submitting {} metadata files to resolve in parallel for {}", toDownloadInParallel.size(), node);
            buildOperationExecutor.runAll(new Action<BuildOperationQueue<RunnableBuildOperation>>() {
                @Override
                public void execute(BuildOperationQueue<RunnableBuildOperation> buildOperationQueue) {
                    for (final EdgeState dependency : toDownloadInParallel) {
                        buildOperationQueue.add(new DownloadMetadataOperation(dependency.getTargetComponent()));
                    }
                }
            });
        }
    }

    private void attachToTargetRevisionsSerially(List<EdgeState> dependencies) {
        // the following only needs to be done serially to preserve ordering of dependencies in the graph: we have visited the edges
        // but we still didn't add the result to the queue. Doing it from resolve threads would result in non-reproducible graphs, where
        // edges could be added in different order. To avoid this, the addition of new edges is done serially.
        for (EdgeState dependency : dependencies) {
            if (dependency.getTargetComponent() != null) {
                dependency.attachToTargetConfigurations();
            }
        }
    }

    private static ComponentIdentifier toComponentId(ModuleVersionIdentifier id, Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache) {
        ComponentIdentifier identifier = componentIdentifierCache.get(id);
        if (identifier == null) {
            identifier = DefaultModuleComponentIdentifier.newId(id);
            componentIdentifierCache.put(id, identifier);
        }
        return identifier;
    }

    private static boolean performPreemptiveDownload(ComponentState state) {
        return state.isSelected();
    }

    /**
     * Populates the result from the graph traversal state.
     */
    private void assembleResult(ResolveState resolveState, DependencyGraphVisitor visitor) {
        visitor.start(resolveState.getRoot());

        // Visit the selectors
        for (DependencyGraphSelector selector : resolveState.getSelectors()) {
            visitor.visitSelector(selector);
        }

        // Visit the nodes prior to visiting the edges
        for (NodeState nodeState : resolveState.getNodes()) {
            if (nodeState.isSelected()) {
                visitor.visitNode(nodeState);
            }
        }

        // Collect the components to sort in consumer-first order
        List<ComponentState> queue = new ArrayList<ComponentState>();
        for (ModuleResolveState module : resolveState.getModules()) {
            if (module.getSelected() != null) {
                queue.add(module.getSelected());
            }
        }

        // Visit the edges after sorting the components in consumer-first order
        while (!queue.isEmpty()) {
            ComponentState component = queue.get(0);
            if (component.getVisitState() == VisitState.NotSeen) {
                component.setVisitState(VisitState.Visiting);
                int pos = 0;
                for (NodeState node : component.getNodes()) {
                    if (!node.isSelected()) {
                        continue;
                    }
                    for (EdgeState edge : node.getIncomingEdges()) {
                        ComponentState owner = edge.getFrom().getOwner();
                        if (owner.getVisitState() == VisitState.NotSeen) {
                            queue.add(pos, owner);
                            pos++;
                        } // else, already visited or currently visiting (which means a cycle), skip
                    }
                }
                if (pos == 0) {
                    // have visited all consumers, so visit this node
                    component.setVisitState(VisitState.Visited);
                    queue.remove(0);
                    for (NodeState node : component.getNodes()) {
                        if (node.isSelected()) {
                            visitor.visitEdges(node);
                        }
                    }
                }
            } else if (component.getVisitState() == VisitState.Visiting) {
                // have visited all consumers, so visit this node
                component.setVisitState(VisitState.Visited);
                queue.remove(0);
                for (NodeState node : component.getNodes()) {
                    if (node.isSelected()) {
                        visitor.visitEdges(node);
                    }
                }
            } else {
                // else, already visited previously, skip
                queue.remove(0);
            }
        }

        visitor.finish(resolveState.getRoot());
    }

    /**
     * Check if all of the supplied selectors agree with the version chosen
     */
    private static boolean allSelectorsAgreeWith(Collection<SelectorState> allSelectors, String version, Predicate<SelectorState> filter) {
        boolean atLeastOneAgrees = false;
        for (SelectorState selectorState : allSelectors) {
            if (filter.apply(selectorState)) {
                ResolvedVersionConstraint versionConstraint = selectorState.getVersionConstraint();
                if (versionConstraint != null) {
                    VersionSelector candidateSelector = versionConstraint.getPreferredSelector();
                    if (candidateSelector == null || !candidateSelector.canShortCircuitWhenVersionAlreadyPreselected() || !candidateSelector.accept(version)) {
                        return false;
                    }
                    candidateSelector = versionConstraint.getRejectedSelector();
                    if (candidateSelector != null && candidateSelector.accept(version)) {
                        return false;
                    }
                    atLeastOneAgrees = true;
                }
            }
        }
        return atLeastOneAgrees;
    }


    enum VisitState {
        NotSeen, Visiting, Visited
    }

}
