/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.jobtracker;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.counter.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.external.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.hadoop.GridHadoopJobPhase.*;
import static org.gridgain.grid.hadoop.GridHadoopTaskType.*;
import static org.gridgain.grid.kernal.processors.hadoop.taskexecutor.GridHadoopTaskState.*;

/**
 * Hadoop job tracker.
 */
public class GridHadoopJobTracker extends GridHadoopComponent {
    /** */
    private final GridMutex mux = new GridMutex();

    /**
     * System cache.
     *
     * @deprecated Object are used for projection for preserving backward compatibility.
     *      Need to return strongly-typed projection (GridHadoopJobId -> GridHadoopJobMetadata)
     *      in the next major release.
     */
    @Deprecated
    private volatile GridCacheProjection<Object, Object> jobMetaPrj;

    /** Map-reduce execution planner. */
    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
    private GridHadoopMapReducePlanner mrPlanner;

    /** All the known jobs. */
    private final ConcurrentMap<GridHadoopJobId, GridFutureAdapterEx<GridHadoopJob>> jobs = new ConcurrentHashMap8<>();

    /** Locally active jobs. */
    private final ConcurrentMap<GridHadoopJobId, JobLocalState> activeJobs = new ConcurrentHashMap8<>();

    /** Locally requested finish futures. */
    private final ConcurrentMap<GridHadoopJobId, GridFutureAdapter<GridHadoopJobId>> activeFinishFuts =
        new ConcurrentHashMap8<>();

    /** Event processing service. */
    private ExecutorService evtProcSvc;

    /** Component busy lock. */
    private GridSpinReadWriteLock busyLock;

    /** Closure to check result of async transform of system cache. */
    private final GridInClosure<GridFuture<?>> failsLogger = new CI1<GridFuture<?>>() {
        @Override public void apply(GridFuture<?> gridFuture) {
            try {
                gridFuture.get();
            }
            catch (GridException e) {
                U.error(log, "Failed to transform system cache.", e);
            }
        }
    };

    /** {@inheritDoc} */
    @Override public void start(GridHadoopContext ctx) throws GridException {
        super.start(ctx);

        busyLock = new GridSpinReadWriteLock();

        evtProcSvc = Executors.newFixedThreadPool(1);
    }

    /**
     * @return Job meta projection.
     */
    @SuppressWarnings("NonPrivateFieldAccessedInSynchronizedContext")
    private GridCacheProjection<Object, Object> jobMetaCache() {
        GridCacheProjection<Object, Object> prj = jobMetaPrj;

        if (prj == null) {
            synchronized (mux) {
                if ((prj = jobMetaPrj) == null) {
                    GridCache<Object, Object> sysCache = ctx.kernalContext().cache().cache(CU.SYS_CACHE_HADOOP_MR);

                    assert sysCache != null;

                    mrPlanner = ctx.planner();

                    try {
                        ctx.kernalContext().resource().injectGeneric(mrPlanner);
                    }
                    catch (GridException e) { // Must not happen.
                        U.error(log, "Failed to inject resources.", e);

                        throw new IllegalStateException(e);
                    }

                    jobMetaPrj = prj = sysCache;
                }
            }
        }

        return prj;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override public void onKernalStart() throws GridException {
        super.onKernalStart();

        GridCacheContinuousQuery<Object, Object> qry = jobMetaCache().queries().createContinuousQuery();

        qry.callback(new GridBiPredicate<UUID,
            Collection<Map.Entry<Object, Object>>>() {
            @Override public boolean apply(UUID nodeId,
                final Collection<Map.Entry<Object, Object>> evts) {
                if (!busyLock.tryReadLock())
                    return false;

                try {
                    // Must process query callback in a separate thread to avoid deadlocks.
                    evtProcSvc.submit(new EventHandler() {
                        @Override protected void body() throws GridException {
                            processJobMetadata(evts);
                        }
                    });

                    return true;
                }
                finally {
                    busyLock.readUnlock();
                }
            }
        });

        qry.execute();

        ctx.kernalContext().event().addLocalEventListener(new GridLocalEventListener() {
            @Override public void onEvent(final GridEvent evt) {
                if (!busyLock.tryReadLock())
                    return;

                try {
                    // Must process discovery callback in a separate thread to avoid deadlock.
                    evtProcSvc.submit(new EventHandler() {
                        @Override protected void body() {
                            processNodeLeft((GridDiscoveryEvent)evt);
                        }
                    });
                }
                finally {
                    busyLock.readUnlock();
                }
            }
        }, GridEventType.EVT_NODE_FAILED, GridEventType.EVT_NODE_LEFT);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        super.onKernalStop(cancel);

        busyLock.writeLock();

        evtProcSvc.shutdown();

        // Fail all pending futures.
        for (GridFutureAdapter<GridHadoopJobId> fut : activeFinishFuts.values())
            fut.onDone(new GridException("Failed to execute Hadoop map-reduce job (grid is stopping)."));
    }

    /**
     * Submits execution of Hadoop job to grid.
     *
     * @param jobId Job ID.
     * @param info Job info.
     * @return Job completion future.
     */
    @SuppressWarnings("unchecked")
    public GridFuture<GridHadoopJobId> submit(GridHadoopJobId jobId, GridHadoopJobInfo info) {
        if (!busyLock.tryReadLock()) {
            return new GridFinishedFutureEx<>(new GridException("Failed to execute map-reduce job " +
                "(grid is stopping): " + info));
        }

        try {
            if (jobs.containsKey(jobId) || jobMetaCache().containsKey(jobId))
                throw new GridException("Failed to submit job. Job with the same ID already exists: " + jobId);

            GridHadoopJob job = job(jobId, info);

            GridHadoopMapReducePlan mrPlan = mrPlanner.preparePlan(job, ctx.nodes(), null);

            GridHadoopJobMetadata meta = new GridHadoopJobMetadata(ctx.localNodeId(), jobId, info);

            meta.mapReducePlan(mrPlan);

            meta.pendingSplits(allSplits(mrPlan));
            meta.pendingReducers(allReducers(mrPlan));

            GridFutureAdapter<GridHadoopJobId> completeFut = new GridFutureAdapter<>();

            GridFutureAdapter<GridHadoopJobId> old = activeFinishFuts.put(jobId, completeFut);

            assert old == null : "Duplicate completion future [jobId=" + jobId + ", old=" + old + ']';

            if (log.isDebugEnabled())
                log.debug("Submitting job metadata [jobId=" + jobId + ", meta=" + meta + ']');

            if (jobMetaCache().putIfAbsent(jobId, meta) != null)
                throw new GridException("Failed to submit job. Job with the same ID already exists: " + jobId);

            return completeFut;
        }
        catch (GridException e) {
            U.error(log, "Failed to submit job: " + jobId, e);

            return new GridFinishedFutureEx<>(e);
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets hadoop job status for given job ID.
     *
     * @param jobId Job ID to get status for.
     * @return Job status for given job ID or {@code null} if job was not found.
     */
    @Nullable public GridHadoopJobStatus status(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null; // Grid is stopping.

        try {
            GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            return meta != null ? GridHadoopUtils.status(meta) : null;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets job finish future.
     *
     * @param jobId Job ID.
     * @return Finish future or {@code null}.
     * @throws GridException If failed.
     */
    @Nullable public GridFuture<?> finishFuture(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null; // Grid is stopping.

        try {
            GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            if (meta == null)
                return null;

            if (log.isTraceEnabled())
                log.trace("Got job metadata for status check [locNodeId=" + ctx.localNodeId() + ", meta=" + meta + ']');

            if (meta.phase() == PHASE_COMPLETE) {
                if (log.isTraceEnabled())
                    log.trace("Job is complete, returning finished future: " + jobId);

                return new GridFinishedFutureEx<>(jobId, meta.failCause());
            }

            GridFutureAdapter<GridHadoopJobId> fut = F.addIfAbsent(activeFinishFuts, jobId,
                new GridFutureAdapter<GridHadoopJobId>());

            // Get meta from cache one more time to close the window.
            meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            if (log.isTraceEnabled())
                log.trace("Re-checking job metadata [locNodeId=" + ctx.localNodeId() + ", meta=" + meta + ']');

            if (meta == null) {
                fut.onDone();

                activeFinishFuts.remove(jobId , fut);
            }
            else if (meta.phase() == PHASE_COMPLETE) {
                fut.onDone(jobId, meta.failCause());

                activeFinishFuts.remove(jobId , fut);
            }

            return fut;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets job plan by job ID.
     *
     * @param jobId Job ID.
     * @return Job plan.
     * @throws GridException If failed.
     */
    public GridHadoopMapReducePlan plan(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null;

        try {
            GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            if (meta != null)
                return meta.mapReducePlan();

            return null;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Callback from task executor invoked when a task has been finished.
     *
     * @param info Task info.
     * @param status Task status.
     */
    @SuppressWarnings({"ConstantConditions", "ThrowableResultOfMethodCallIgnored"})
    public void onTaskFinished(GridHadoopTaskInfo info, GridHadoopTaskStatus status) {
        if (!busyLock.tryReadLock())
            return;

        try {
            assert status.state() != RUNNING;

            if (log.isDebugEnabled())
                log.debug("Received task finished callback [info=" + info + ", status=" + status + ']');

            JobLocalState state = activeJobs.get(info.jobId());

            // Task CRASHes with null fail cause.
            assert (status.state() != FAILED) || status.failCause() != null :
                "Invalid task status [info=" + info + ", status=" + status + ']';

            assert state != null || (ctx.jobUpdateLeader() && (info.type() == COMMIT || info.type() == ABORT)):
                "Missing local state for finished task [info=" + info + ", status=" + status + ']';

            StackedClosure incrCntrs = null;

            if (status.state() == COMPLETED)
                incrCntrs = new IncrementCountersClosure(null, status.counters());

            switch (info.type()) {
                case SETUP: {
                    state.onSetupFinished(info, status, incrCntrs);

                    break;
                }

                case MAP: {
                    state.onMapFinished(info, status, incrCntrs);

                    break;
                }

                case REDUCE: {
                    state.onReduceFinished(info, status, incrCntrs);

                    break;
                }

                case COMBINE: {
                    state.onCombineFinished(info, status, incrCntrs);

                    break;
                }

                case COMMIT:
                case ABORT: {
                    GridCacheEntry<Object, Object> entry = jobMetaCache().entry(info.jobId());

                    entry.timeToLive(ctx.configuration().getFinishedJobInfoTtl());

                    entry.transformAsync(new UpdatePhaseClosure(incrCntrs, PHASE_COMPLETE)).listenAsync(failsLogger);

                    break;
                }
            }
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * @param jobId Job id.
     * @param c Closure of operation.
     */
    private void transform(GridHadoopJobId jobId, GridClosure<Object, Object> c) {
        jobMetaCache().transformAsync(jobId, c).listenAsync(failsLogger);
    }

    /**
     * Callback from task executor called when process is ready to received shuffle messages.
     *
     * @param jobId Job ID.
     * @param reducers Reducers.
     * @param desc Process descriptor.
     */
    public void onExternalMappersInitialized(GridHadoopJobId jobId, Collection<Integer> reducers,
        GridHadoopProcessDescriptor desc) {
        transform(jobId, new InitializeReducersClosure(null, reducers, desc));
    }

    /**
     * Gets all input splits for given hadoop map-reduce plan.
     *
     * @param plan Map-reduce plan.
     * @return Collection of all input splits that should be processed.
     */
    @SuppressWarnings("ConstantConditions")
    private Collection<GridHadoopInputSplit> allSplits(GridHadoopMapReducePlan plan) {
        Collection<GridHadoopInputSplit> res = new HashSet<>();

        for (UUID nodeId : plan.mapperNodeIds())
            res.addAll(plan.mappers(nodeId));

        return res;
    }

    /**
     * Gets all reducers for this job.
     *
     * @param plan Map-reduce plan.
     * @return Collection of reducers.
     */
    private Collection<Integer> allReducers(GridHadoopMapReducePlan plan) {
        Collection<Integer> res = new HashSet<>();

        for (int i = 0; i < plan.reducers(); i++)
            res.add(i);

        return res;
    }

    /**
     * Processes node leave (or fail) event.
     *
     * @param evt Discovery event.
     */
    @SuppressWarnings("ConstantConditions")
    private void processNodeLeft(GridDiscoveryEvent evt) {
        if (log.isDebugEnabled())
            log.debug("Processing discovery event [locNodeId=" + ctx.localNodeId() + ", evt=" + evt + ']');

        // Check only if this node is responsible for job status updates.
        if (ctx.jobUpdateLeader()) {
            boolean checkSetup = evt.eventNode().order() < ctx.localNodeOrder();

            // Iteration over all local entries is correct since system cache is REPLICATED.
            for (Object metaObj : jobMetaCache().values()) {
                GridHadoopJobMetadata meta = (GridHadoopJobMetadata)metaObj;

                GridHadoopJobId jobId = meta.jobId();

                GridHadoopMapReducePlan plan = meta.mapReducePlan();

                GridHadoopJobPhase phase = meta.phase();

                try {
                    if (checkSetup && phase == PHASE_SETUP && !activeJobs.containsKey(jobId)) {
                        // Failover setup task.
                        GridHadoopJob job = job(jobId, meta.jobInfo());

                        Collection<GridHadoopTaskInfo> setupTask = setupTask(jobId);

                        assert setupTask != null;

                        ctx.taskExecutor().run(job, setupTask);
                    }
                    else if (phase == PHASE_MAP || phase == PHASE_REDUCE) {
                        // Must check all nodes, even that are not event node ID due to
                        // multiple node failure possibility.
                        Collection<GridHadoopInputSplit> cancelSplits = null;

                        for (UUID nodeId : plan.mapperNodeIds()) {
                            if (ctx.kernalContext().discovery().node(nodeId) == null) {
                                // Node has left the grid.
                                Collection<GridHadoopInputSplit> mappers = plan.mappers(nodeId);

                                if (cancelSplits == null)
                                    cancelSplits = new HashSet<>();

                                cancelSplits.addAll(mappers);
                            }
                        }

                        Collection<Integer> cancelReducers = null;

                        for (UUID nodeId : plan.reducerNodeIds()) {
                            if (ctx.kernalContext().discovery().node(nodeId) == null) {
                                // Node has left the grid.
                                int[] reducers = plan.reducers(nodeId);

                                if (cancelReducers == null)
                                    cancelReducers = new HashSet<>();

                                for (int rdc : reducers)
                                    cancelReducers.add(rdc);
                            }
                        }

                        if (cancelSplits != null || cancelReducers != null)
                            jobMetaCache().transform(meta.jobId(), new CancelJobClosure(null, new GridException(
                                "One or more nodes participating in map-reduce job execution failed."), cancelSplits,
                                cancelReducers));
                    }
                }
                catch (GridException e) {
                    U.error(log, "Failed to cancel job: " + meta, e);
                }
            }
        }
    }

    /**
     * @param updated Updated cache entries.
     */
    private void processJobMetadata(Iterable<Map.Entry<Object, Object>> updated)
        throws GridException {
        UUID locNodeId = ctx.localNodeId();

        for (Map.Entry<Object, Object> entry : updated) {
            GridHadoopJobId jobId = (GridHadoopJobId)entry.getKey();
            GridHadoopJobMetadata meta = (GridHadoopJobMetadata)entry.getValue();

            if (meta == null || !ctx.isParticipating(meta))
                continue;

            if (log.isDebugEnabled())
                log.debug("Processing job metadata update callback [locNodeId=" + locNodeId +
                    ", meta=" + meta + ']');

            try {
                ctx.taskExecutor().onJobStateChanged(meta);
            }
            catch (GridException e) {
                U.error(log, "Failed to process job state changed callback (will fail the job) " +
                    "[locNodeId=" + locNodeId + ", jobId=" + jobId + ", meta=" + meta + ']', e);

                transform(jobId, new CancelJobClosure(null, e));

                continue;
            }

            JobLocalState state = activeJobs.get(jobId);

            GridHadoopJob job = job(jobId, meta.jobInfo());

            GridHadoopMapReducePlan plan = meta.mapReducePlan();

            switch (meta.phase()) {
                case PHASE_SETUP: {
                    if (ctx.jobUpdateLeader()) {
                        Collection<GridHadoopTaskInfo> setupTask = setupTask(jobId);

                        if (setupTask != null)
                            ctx.taskExecutor().run(job, setupTask);
                    }

                    break;
                }

                case PHASE_MAP: {
                    // Check if we should initiate new task on local node.
                    Collection<GridHadoopTaskInfo> tasks = mapperTasks(plan.mappers(locNodeId), meta);

                    if (tasks != null)
                        ctx.taskExecutor().run(job, tasks);

                    break;
                }

                case PHASE_REDUCE: {
                    if (meta.pendingReducers().isEmpty() && ctx.jobUpdateLeader()) {
                        GridHadoopTaskInfo info = new GridHadoopTaskInfo(ctx.localNodeId(), COMMIT, jobId, 0, 0, null);

                        if (log.isDebugEnabled())
                            log.debug("Submitting COMMIT task for execution [locNodeId=" + locNodeId +
                                ", jobId=" + jobId + ']');

                        ctx.taskExecutor().run(job, Collections.singletonList(info));

                        return;
                    }

                    Collection<GridHadoopTaskInfo> tasks = reducerTasks(plan.reducers(locNodeId), job);

                    if (tasks != null)
                        ctx.taskExecutor().run(job, tasks);

                    break;
                }

                case PHASE_CANCELLING: {
                    // Prevent multiple task executor notification.
                    if (state != null && state.onCancel()) {
                        if (log.isDebugEnabled())
                            log.debug("Cancelling local task execution for job: " + meta);

                        ctx.taskExecutor().cancelTasks(jobId);
                    }

                    if (meta.pendingSplits().isEmpty() && meta.pendingReducers().isEmpty()) {
                        if (ctx.jobUpdateLeader()) {
                            if (state == null)
                                state = initState(jobId);

                            // Prevent running multiple abort tasks.
                            if (state.onAborted()) {
                                GridHadoopTaskInfo info = new GridHadoopTaskInfo(ctx.localNodeId(), ABORT, jobId, 0, 0,
                                    null);

                                if (log.isDebugEnabled())
                                    log.debug("Submitting ABORT task for execution [locNodeId=" + locNodeId +
                                        ", jobId=" + jobId + ']');

                                ctx.taskExecutor().run(job, Collections.singletonList(info));
                            }
                        }

                        return;
                    }
                    else {
                        // Check if there are unscheduled mappers or reducers.
                        Collection<GridHadoopInputSplit> cancelMappers = new ArrayList<>();
                        Collection<Integer> cancelReducers = new ArrayList<>();

                        Collection<GridHadoopInputSplit> mappers = plan.mappers(ctx.localNodeId());

                        if (mappers != null) {
                            for (GridHadoopInputSplit b : mappers) {
                                if (state == null || !state.mapperScheduled(b))
                                    cancelMappers.add(b);
                            }
                        }

                        int[] rdc = plan.reducers(ctx.localNodeId());

                        if (rdc != null) {
                            for (int r : rdc) {
                                if (state == null || !state.reducerScheduled(r))
                                    cancelReducers.add(r);
                            }
                        }

                        if (!cancelMappers.isEmpty() || !cancelReducers.isEmpty())
                            transform(jobId, new CancelJobClosure(null, cancelMappers, cancelReducers));
                    }

                    break;
                }

                case PHASE_COMPLETE: {
                    if (log.isDebugEnabled())
                        log.debug("Job execution is complete, will remove local state from active jobs " +
                            "[jobId=" + jobId + ", meta=" + meta +
                            ", setupTime=" + meta.setupTime() +
                            ", mapTime=" + meta.mapTime() +
                            ", reduceTime=" + meta.reduceTime() +
                            ", totalTime=" + meta.totalTime() + ']');

                    if (state != null) {
                        state = activeJobs.remove(jobId);

                        assert state != null;

                        ctx.shuffle().jobFinished(jobId);
                    }

                    GridFutureAdapter<GridHadoopJobId> finishFut = activeFinishFuts.remove(jobId);

                    if (finishFut != null) {
                        if (log.isDebugEnabled())
                            log.debug("Completing job future [locNodeId=" + locNodeId + ", meta=" + meta + ']');

                        finishFut.onDone(jobId, meta.failCause());
                    }

                    if (ctx.jobUpdateLeader())
                        job.cleanupStagingDirectory();

                    GridFutureAdapterEx<GridHadoopJob> jobFut = jobs.get(jobId);

                    if (jobFut.get() == job && jobs.remove(jobId, jobFut))
                        job.dispose(false);
                    else
                        assert false;

                    break;
                }

                default:
                    assert false;
            }
        }
    }

    /**
     * Creates setup task based on job information.
     *
     * @param jobId Job ID.
     * @return Setup task wrapped in collection.
     */
    @Nullable private Collection<GridHadoopTaskInfo> setupTask(GridHadoopJobId jobId) {
        if (activeJobs.containsKey(jobId))
            return null;
        else {
            initState(jobId);

            return Collections.singleton(new GridHadoopTaskInfo(ctx.localNodeId(), SETUP, jobId, 0, 0, null));
        }
    }

    /**
     * Creates mapper tasks based on job information.
     *
     * @param mappers Mapper blocks.
     * @param meta Job metadata.
     * @return Collection of created task infos or {@code null} if no mapper tasks scheduled for local node.
     */
    private Collection<GridHadoopTaskInfo> mapperTasks(Iterable<GridHadoopInputSplit> mappers, GridHadoopJobMetadata meta) {
        UUID locNodeId = ctx.localNodeId();
        GridHadoopJobId jobId = meta.jobId();

        JobLocalState state = activeJobs.get(jobId);

        Collection<GridHadoopTaskInfo> tasks = null;

        if (mappers != null) {
            if (state == null)
                state = initState(jobId);

            for (GridHadoopInputSplit split : mappers) {
                if (state.addMapper(split)) {
                    if (log.isDebugEnabled())
                        log.debug("Submitting MAP task for execution [locNodeId=" + locNodeId +
                            ", split=" + split + ']');

                    GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId, MAP, jobId, meta.taskNumber(split),
                        0, split);

                    if (tasks == null)
                        tasks = new ArrayList<>();

                    tasks.add(taskInfo);
                }
            }
        }

        return tasks;
    }

    /**
     * Creates reducer tasks based on job information.
     *
     * @param reducers Reducers (may be {@code null}).
     * @param job Job instance.
     * @return Collection of task infos.
     */
    private Collection<GridHadoopTaskInfo> reducerTasks(int[] reducers, GridHadoopJob job) {
        UUID locNodeId = ctx.localNodeId();
        GridHadoopJobId jobId = job.id();

        JobLocalState state = activeJobs.get(jobId);

        Collection<GridHadoopTaskInfo> tasks = null;

        if (reducers != null) {
            if (state == null)
                state = initState(job.id());

            for (int rdc : reducers) {
                if (state.addReducer(rdc)) {
                    if (log.isDebugEnabled())
                        log.debug("Submitting REDUCE task for execution [locNodeId=" + locNodeId +
                            ", rdc=" + rdc + ']');

                    GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId, REDUCE, jobId, rdc, 0, null);

                    if (tasks == null)
                        tasks = new ArrayList<>();

                    tasks.add(taskInfo);
                }
            }
        }

        return tasks;
    }

    /**
     * Initializes local state for given job metadata.
     *
     * @param jobId Job ID.
     * @return Local state.
     */
    private JobLocalState initState(GridHadoopJobId jobId) {
        return F.addIfAbsent(activeJobs, jobId, new JobLocalState());
    }

    /**
     * Gets or creates job instance.
     *
     * @param jobId Job ID.
     * @param jobInfo Job info.
     * @return Job.
     * @throws GridException If failed.
     */
    @Nullable public GridHadoopJob job(GridHadoopJobId jobId, @Nullable GridHadoopJobInfo jobInfo) throws GridException {
        GridFutureAdapterEx<GridHadoopJob> fut = jobs.get(jobId);

        if (fut != null || (fut = jobs.putIfAbsent(jobId, new GridFutureAdapterEx<GridHadoopJob>())) != null)
            return fut.get();

        fut = jobs.get(jobId);

        GridHadoopJob job = null;

        try {
            if (jobInfo == null) {
                GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

                if (meta == null)
                    throw new GridException("Failed to find job metadata for ID: " + jobId);

                jobInfo = meta.jobInfo();
            }

            job = jobInfo.createJob(jobId, log);

            job.initialize(false, ctx.localNodeId());

            fut.onDone(job);

            return job;
        }
        catch (GridException e) {
            fut.onDone(e);

            jobs.remove(jobId, fut);

            if (job != null) {
                try {
                    job.dispose(false);
                }
                catch (GridException e0) {
                    U.error(log, "Failed to dispose job: " + jobId, e0);
                }
            }

            throw e;
        }
    }

    /**
     * Kills job.
     *
     * @param jobId Job ID.
     * @return {@code True} if job was killed.
     * @throws GridException If failed.
     */
    public boolean killJob(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return false; // Grid is stopping.

        try {
            GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            if (meta != null && meta.phase() != PHASE_COMPLETE && meta.phase() != PHASE_CANCELLING) {
                GridHadoopTaskCancelledException err = new GridHadoopTaskCancelledException("Job cancelled.");

                jobMetaCache().transform(jobId, new CancelJobClosure(null, err));
            }
        }
        finally {
            busyLock.readUnlock();
        }

        GridFuture<?> fut = finishFuture(jobId);

        if (fut != null) {
            try {
                fut.get();
            } catch (Throwable e) {
                if (e.getCause() instanceof GridHadoopTaskCancelledException)
                    return true;
            }
        }

        return false;
    }

    /**
     * Returns job counters.
     *
     * @param jobId Job identifier.
     * @return Job counters or {@code null} if job cannot be found.
     * @throws GridException If failed.
     */
    @Nullable public GridHadoopCounters jobCounters(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null;

        try {
            final GridHadoopJobMetadata meta = (GridHadoopJobMetadata)jobMetaCache().get(jobId);

            return meta != null ? meta.counters() : null;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Event handler protected by busy lock.
     */
    private abstract class EventHandler implements Runnable {
        /** {@inheritDoc} */
        @Override public void run() {
            if (!busyLock.tryReadLock())
                return;

            try {
                body();
            }
            catch (Throwable e) {
                U.error(log, "Unhandled exception while processing event.", e);
            }
            finally {
                busyLock.readUnlock();
            }
        }

        /**
         * Handler body.
         */
        protected abstract void body() throws Exception;
    }

    /**
     *
     */
    private class JobLocalState {
        /** Mappers. */
        private final Collection<GridHadoopInputSplit> currMappers = new HashSet<>();

        /** Reducers. */
        private final Collection<Integer> currReducers = new HashSet<>();

        /** Number of completed mappers. */
        private final AtomicInteger completedMappersCnt = new AtomicInteger();

        /** Cancelled flag. */
        private boolean cancelled;

        /** Aborted flag. */
        private boolean aborted;

        /**
         * @param mapSplit Map split to add.
         * @return {@code True} if mapper was added.
         */
        private boolean addMapper(GridHadoopInputSplit mapSplit) {
            return currMappers.add(mapSplit);
        }

        /**
         * @param rdc Reducer number to add.
         * @return {@code True} if reducer was added.
         */
        private boolean addReducer(int rdc) {
            return currReducers.add(rdc);
        }

        /**
         * Checks whether this split was scheduled for given attempt.
         *
         * @param mapSplit Map split to check.
         * @return {@code True} if mapper was scheduled.
         */
        public boolean mapperScheduled(GridHadoopInputSplit mapSplit) {
            return currMappers.contains(mapSplit);
        }

        /**
         * Checks whether this split was scheduled for given attempt.
         *
         * @param rdc Reducer number to check.
         * @return {@code True} if reducer was scheduled.
         */
        public boolean reducerScheduled(int rdc) {
            return currReducers.contains(rdc);
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         * @param prev Previous closure.
         */
        private void onSetupFinished(final GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status, StackedClosure prev) {
            final GridHadoopJobId jobId = taskInfo.jobId();

            if (status.state() == FAILED || status.state() == CRASHED)
                transform(jobId, new CancelJobClosure(prev, status.failCause()));
            else
                transform(jobId, new UpdatePhaseClosure(prev, PHASE_MAP));
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         * @param prev Previous closure.
         */
        private void onMapFinished(final GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status,
            final StackedClosure prev) {
            final GridHadoopJobId jobId = taskInfo.jobId();

            boolean lastMapperFinished = completedMappersCnt.incrementAndGet() == currMappers.size();

            if (status.state() == FAILED || status.state() == CRASHED) {
                // Fail the whole job.
                transform(jobId, new RemoveMappersClosure(prev, taskInfo.inputSplit(), status.failCause()));

                return;
            }

            GridInClosure<GridFuture<?>> cacheUpdater = new CIX1<GridFuture<?>>() {
                @Override public void applyx(GridFuture<?> f) {
                    Throwable err = null;

                    if (f != null) {
                        try {
                            f.get();
                        }
                        catch (GridException e) {
                            err = e;
                        }
                    }

                    transform(jobId, new RemoveMappersClosure(prev, taskInfo.inputSplit(), err));
                }
            };

            if (lastMapperFinished)
                ctx.shuffle().flush(jobId).listenAsync(cacheUpdater);
            else
                cacheUpdater.apply(null);
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         * @param prev Previous closure.
         */
        private void onReduceFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status, StackedClosure prev) {
            GridHadoopJobId jobId = taskInfo.jobId();
            if (status.state() == FAILED || status.state() == CRASHED)
                // Fail the whole job.
                transform(jobId, new RemoveReducerClosure(prev, taskInfo.taskNumber(), status.failCause()));
            else
                transform(jobId, new RemoveReducerClosure(prev, taskInfo.taskNumber()));
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         * @param prev Previous closure.
         */
        private void onCombineFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status,
            final StackedClosure prev) {
            final GridHadoopJobId jobId = taskInfo.jobId();

            if (status.state() == FAILED || status.state() == CRASHED)
                // Fail the whole job.
                transform(jobId, new RemoveMappersClosure(prev, currMappers, status.failCause()));
            else {
                ctx.shuffle().flush(jobId).listenAsync(new CIX1<GridFuture<?>>() {
                    @Override public void applyx(GridFuture<?> f) {
                        Throwable err = null;

                        if (f != null) {
                            try {
                                f.get();
                            }
                            catch (GridException e) {
                                err = e;
                            }
                        }

                        transform(jobId, new RemoveMappersClosure(prev, currMappers, err));
                    }
                });
            }
        }

        /**
         * @return {@code True} if job was cancelled by this (first) call.
         */
        public boolean onCancel() {
            if (!cancelled && !aborted) {
                cancelled = true;

                return true;
            }

            return false;
        }

        /**
         * @return {@code True} if job was aborted this (first) call.
         */
        public boolean onAborted() {
            if (!aborted) {
                aborted = true;

                return true;
            }

            return false;
        }
    }

    /**
     * Update job phase transform closure.
     */
    private static class UpdatePhaseClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** Phase to update. */
        private final GridHadoopJobPhase phase;

        /**
         * @param prev Previous closure.
         * @param phase Phase to update.
         */
        private UpdatePhaseClosure(@Nullable StackedClosure prev, GridHadoopJobPhase phase) {
            super(prev);

            this.phase = phase;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            cp.phase(phase);

            if (phase == PHASE_MAP)
                cp.setupCompleteTimestamp(System.currentTimeMillis());
            else if (phase == PHASE_COMPLETE)
                cp.completeTimestamp(System.currentTimeMillis());
        }
    }

    /**
     * Remove mapper transform closure.
     */
    private static class RemoveMappersClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** Mapper split to remove. */
        private final Collection<GridHadoopInputSplit> splits;

        /** Error. */
        private final Throwable err;

        /**
         * @param prev Previous closure.
         * @param split Mapper split to remove.
         * @param err Error.
         */
        private RemoveMappersClosure(@Nullable StackedClosure prev, GridHadoopInputSplit split, Throwable err) {
            this(prev, Collections.singletonList(split), err);
        }

        /**
         * @param prev Previous closure.
         * @param splits Mapper splits to remove.
         */
        private RemoveMappersClosure(@Nullable StackedClosure prev, Collection<GridHadoopInputSplit> splits,
            Throwable err) {
            super(prev);

            this.splits = splits;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            Collection<GridHadoopInputSplit> splitsCp = new HashSet<>(cp.pendingSplits());

            splitsCp.removeAll(splits);

            cp.pendingSplits(splitsCp);

            if (cp.phase() != PHASE_CANCELLING && err != null)
                cp.failCause(err);

            if (err != null)
                cp.phase(PHASE_CANCELLING);

            if (splitsCp.isEmpty()) {
                if (cp.phase() != PHASE_CANCELLING) {
                    cp.phase(PHASE_REDUCE);

                    cp.mapCompleteTimestamp(System.currentTimeMillis());
                }
            }
        }
    }

    /**
     * Remove reducer transform closure.
     */
    private static class RemoveReducerClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** Mapper split to remove. */
        private final int rdc;

        /** Error. */
        private Throwable err;

        /**
         * @param prev Previous closure.
         * @param rdc Reducer to remove.
         */
        private RemoveReducerClosure(@Nullable StackedClosure prev, int rdc) {
            super(prev);

            this.rdc = rdc;
        }

        /**
         * @param prev Previous closure.
         * @param rdc Reducer to remove.
         */
        private RemoveReducerClosure(@Nullable StackedClosure prev, int rdc, Throwable err) {
            super(prev);

            this.rdc = rdc;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            Collection<Integer> rdcCp = new HashSet<>(cp.pendingReducers());

            rdcCp.remove(rdc);

            cp.pendingReducers(rdcCp);

            if (err != null) {
                cp.phase(PHASE_CANCELLING);
                cp.failCause(err);
            }
        }
    }

    /**
     * Initialize reducers.
     */
    private static class InitializeReducersClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** Reducers. */
        private final Collection<Integer> rdc;

        /** Process descriptor for reducers. */
        private final GridHadoopProcessDescriptor desc;

        /**
         * @param prev Previous closure.
         * @param rdc Reducers to initialize.
         * @param desc External process descriptor.
         */
        private InitializeReducersClosure(@Nullable StackedClosure prev, Collection<Integer> rdc,
            GridHadoopProcessDescriptor desc) {
            super(prev);

            assert !F.isEmpty(rdc);
            assert desc != null;

            this.rdc = rdc;
            this.desc = desc;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            Map<Integer, GridHadoopProcessDescriptor> oldMap = meta.reducersAddresses();

            Map<Integer, GridHadoopProcessDescriptor> rdcMap = oldMap == null ?
                new HashMap<Integer, GridHadoopProcessDescriptor>() : new HashMap<>(oldMap);

            for (Integer r : rdc)
                rdcMap.put(r, desc);

            cp.reducersAddresses(rdcMap);
        }
    }

    /**
     * Remove reducer transform closure.
     */
    private static class CancelJobClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** Mapper split to remove. */
        private final Collection<GridHadoopInputSplit> splits;

        /** Reducers to remove. */
        private final Collection<Integer> rdc;

        /** Error. */
        private final Throwable err;

        /**
         * @param prev Previous closure.
         * @param err Fail cause.
         */
        private CancelJobClosure(@Nullable StackedClosure prev, Throwable err) {
            this(prev, err, null, null);
        }

        /**
         * @param prev Previous closure.
         * @param splits Splits to remove.
         * @param rdc Reducers to remove.
         */
        private CancelJobClosure(@Nullable StackedClosure prev, Collection<GridHadoopInputSplit> splits,
            Collection<Integer> rdc) {
            this(prev, null, splits, rdc);
        }

        /**
         * @param prev Previous closure.
         * @param err Error.
         * @param splits Splits to remove.
         * @param rdc Reducers to remove.
         */
        private CancelJobClosure(@Nullable StackedClosure prev, Throwable err, Collection<GridHadoopInputSplit> splits,
            Collection<Integer> rdc) {
            super(prev);

            this.splits = splits;
            this.rdc = rdc;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            assert meta.phase() == PHASE_CANCELLING || err != null: "Invalid phase for cancel: " + meta;

            Collection<Integer> rdcCp = new HashSet<>(cp.pendingReducers());

            if (rdc != null)
                rdcCp.removeAll(rdc);

            cp.pendingReducers(rdcCp);

            Collection<GridHadoopInputSplit> splitsCp = new HashSet<>(cp.pendingSplits());

            if (splits != null)
                splitsCp.removeAll(splits);

            cp.pendingSplits(splitsCp);

            cp.phase(PHASE_CANCELLING);

            if (err != null)
                cp.failCause(err);
        }
    }

    /**
     * Increment counter values closure.
     */
    private static class IncrementCountersClosure extends StackedClosure {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final GridHadoopCounters counters;

        /**
         * @param prev Previous closure.
         * @param counters Task counters to add into job counters.
         */
        private IncrementCountersClosure(@Nullable StackedClosure prev, GridHadoopCounters counters) {
            super(prev);

            assert counters != null;

            this.counters = counters;
        }

        /** {@inheritDoc} */
        @Override protected void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp) {
            GridHadoopCounters cntrs = new GridHadoopCountersImpl(cp.counters());

            cntrs.merge(counters);

            cp.counters(cntrs);
        }
    }

    /**
     * Abstract stacked closure.
     */
    private abstract static class StackedClosure implements GridClosure<Object, Object> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final StackedClosure prev;

        /**
         * @param prev Previous closure.
         */
        private StackedClosure(@Nullable StackedClosure prev) {
            this.prev = prev;
        }

        /** {@inheritDoc} */
        @Override public final GridHadoopJobMetadata apply(Object meta) {
            if (meta == null)
                return null;

            GridHadoopJobMetadata cp = prev != null ? prev.apply(meta) : new GridHadoopJobMetadata((GridHadoopJobMetadata)meta);

            update((GridHadoopJobMetadata)meta, cp);

            return cp;
        }

        /**
         * Update given job metadata object.
         *
         * @param meta Initial job metadata.
         * @param cp Copy.
         */
        protected abstract void update(GridHadoopJobMetadata meta, GridHadoopJobMetadata cp);
    }
}
