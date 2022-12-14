/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.submission;

import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.externalcontext.AsyncRequest;
import org.orbeon.oxf.externalcontext.ExternalContext;
import org.orbeon.oxf.externalcontext.LocalExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.event.XFormsEvents;

import javax.enterprise.concurrent.ManagedExecutorService;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.concurrent.*;

/**
 * Handle asynchronous submissions.
 *
 * The CompletionService is stored in the session, indexed by document UUID.
 *
 * See https://doc.orbeon.com/xforms/submission-asynchronous.html
 * See http://java.sun.com/j2se/1.5.0/docs/api/java/util/concurrent/ExecutorCompletionService.html
 */
public class AsynchronousSubmissionManager {

    private static final String ASYNC_SUBMISSIONS_SESSION_KEY_PREFIX = "oxf.xforms.state.async-submissions.";

    // Global thread pool
    private static ExecutorService threadPool = null;

    private final XFormsContainingDocument containingDocument;

    public AsynchronousSubmissionManager(XFormsContainingDocument containingDocument) {
        this.containingDocument = containingDocument;
    }

    private static ExecutorService getExecutorService() {
        try {
            // If the app server gives us an `ExecutorService` (e.g. with WildFly), use it
            // (See ??EE.5.21, page 146 of the Java EE 7 spec)
            return InitialContext.<ManagedExecutorService>doLookup("java:comp/DefaultManagedExecutorService");
        } catch (NamingException e) {
            // If no `ExecutorService` is provided by the app server (e.g. with Tomcat), use our global thread pool
            synchronized (AsynchronousSubmissions.class) {
                if (threadPool == null)
                    threadPool = Executors.newCachedThreadPool();
            }
            return threadPool;
        }
    }

    /**
     * Add a special delay event to the containing document if there are pending submissions.
     *
     * This should be called just before sending an Ajax response.
     */
    public void addClientDelayEventIfNeeded() {
        if (hasPendingAsynchronousSubmissions()) {
            // NOTE: Could get isShowProgress() from submission, but default must be false
            containingDocument.addDelayedEvent(
                XFormsEvents.XXFORMS_POLL,
                containingDocument.getEffectiveId(),
                false,
                false,
                System.currentTimeMillis() + containingDocument.getSubmissionPollDelay(),
                true,
                false,
                false // no need for duplicates
            );
        }
    }

    private static String getSessionKey(XFormsContainingDocument containingDocument) {
        return getSessionKey(containingDocument.getUUID());
    }

    private static String getSessionKey(String documentUUID) {
        return ASYNC_SUBMISSIONS_SESSION_KEY_PREFIX + documentUUID;
    }

    private static AsynchronousSubmissions getAsynchronousSubmissions(boolean create, String sessionKey) {
        final ExternalContext.Session session = NetUtils.getExternalContext().getRequest().getSession(true);
        final AsynchronousSubmissions existingAsynchronousSubmissions = (AsynchronousSubmissions) session.javaGetAttribute(sessionKey);
        if (existingAsynchronousSubmissions != null) {
            return existingAsynchronousSubmissions;
        } else if (create) {
            final AsynchronousSubmissions asynchronousSubmissions = new AsynchronousSubmissions();
            session.javaSetAttribute(sessionKey, asynchronousSubmissions);
            return asynchronousSubmissions;
        } else {
            return null;
        }
    }

    public void addAsynchronousSubmission(final Callable<SubmissionResult> callable) {

        final AsynchronousSubmissions asynchronousSubmissions = getAsynchronousSubmissions(true, getSessionKey(containingDocument));

        // NOTE: If we want to re-enable foreground async submissions, we must:
        // - do a better detection: !(xf-submit-done/xf-submit-error listener) && replace="none"
        // - OR provide an explicit hint on xf:submission
        asynchronousSubmissions.submit(new Callable<SubmissionResult>() {

            // Submission should not need an ExternalContext, but if it does we must provide access to a safe one
            final ExternalContext currentExternalContext = NetUtils.getExternalContext();
            final ExternalContext newExternalContext = new LocalExternalContext(
                currentExternalContext.getWebAppContext(),
                new AsyncRequest(currentExternalContext.getRequest()),
                currentExternalContext.getResponse());

            public SubmissionResult call() throws Exception {
                // Make sure an ExternalContext is scoped for the callable. We use the same external context as the caller,
                // even though that can be a dangerous. Should we use AsyncExternalContext here?
                // Candidate for Scala withPipelineContext
                final PipelineContext pipelineContext = new PipelineContext();
                pipelineContext.setAttribute(PipelineContext.EXTERNAL_CONTEXT, newExternalContext);
                boolean success = false;
                try {
                    // Perform call
                    final SubmissionResult result = callable.call();
                    success = true;
                    return result;
                } finally {
                    pipelineContext.destroy(success);
                }
            }
        });
    }

    public boolean hasPendingAsynchronousSubmissions() {
        final AsynchronousSubmissions asynchronousSubmissions = getAsynchronousSubmissions(false, getSessionKey(containingDocument));
        return asynchronousSubmissions != null && asynchronousSubmissions.getPendingCount() > 0;
    }

    /**
     * Process all pending asynchronous submissions if any. If processing of a particular submission causes new
     * asynchronous submissions to be started, also wait for the completion of those.
     *
     * Submissions are processed in the order in which they are made available upon termination by the completion
     * service.
     */
    public void processAllAsynchronousSubmissions() {
        final AsynchronousSubmissions asynchronousSubmissions = getAsynchronousSubmissions(false, getSessionKey(containingDocument));
        if (asynchronousSubmissions != null && asynchronousSubmissions.getPendingCount() > 0) {

            final IndentedLogger indentedLogger = containingDocument.getIndentedLogger(XFormsModelSubmission.LOGGING_CATEGORY);
            indentedLogger.startHandleOperation("", "processing all background asynchronous submissions");
            int processedCount = 0;
            try {
                while (asynchronousSubmissions.getPendingCount() > 0) {
                    try {
                        // Handle next completed task
                        final Future<SubmissionResult> future = asynchronousSubmissions.take();
                        final SubmissionResult result = future.get();

                        // Process response by dispatching an event to the submission
                        final XFormsModelSubmission submission = (XFormsModelSubmission) containingDocument.getObjectByEffectiveId(result.getSubmissionEffectiveId());
                        submission.doSubmitReplace(result);

                    } catch (Throwable throwable) {
                        // Something bad happened
                        throw new OXFException(throwable);
                    }

                    processedCount++;
                }
            } finally {
                indentedLogger.endHandleOperation("processed", Integer.toString(processedCount));
            }
        }
    }

    /**
     * Process all completed asynchronous submissions if any. This method returns as soon as no completed submission is
     * available.
     *
     * Submissions are processed in the order in which they are made available upon termination by the completion
     * service.
     */
    public void processCompletedAsynchronousSubmissions() {
        final AsynchronousSubmissions asynchronousSubmissions = getAsynchronousSubmissions(false, getSessionKey(containingDocument));
        if (asynchronousSubmissions != null && asynchronousSubmissions.getPendingCount() > 0) {
            final IndentedLogger indentedLogger = containingDocument.getIndentedLogger(XFormsModelSubmission.LOGGING_CATEGORY);
            indentedLogger.startHandleOperation("", "processing completed background asynchronous submissions");

            int processedCount = 0;
            try {
                Future<SubmissionResult> future = asynchronousSubmissions.poll();
                while (future != null) {
                    try {
                        // Handle next completed task
                        final SubmissionResult result = future.get();

                        // Process response by dispatching an event to the submission
                        final XFormsModelSubmission submission = (XFormsModelSubmission) containingDocument.getObjectByEffectiveId(result.getSubmissionEffectiveId());
                        submission.doSubmitReplace(result);
                    } catch (Throwable throwable) {
                        // Something bad happened
                        throw new OXFException(throwable);
                    }

                    processedCount++;

                    future = asynchronousSubmissions.poll();
                }
            } finally {
                indentedLogger.endHandleOperation("processed", Integer.toString(processedCount),
                        "pending", Integer.toString(asynchronousSubmissions.getPendingCount()));
            }
        }
    }

    private static class AsynchronousSubmissions {
        private final CompletionService<SubmissionResult> completionService =
                new ExecutorCompletionService<SubmissionResult>(getExecutorService());
        private int pendingCount = 0;

        public Future<SubmissionResult> submit(Callable<SubmissionResult> task) {
            final Future<SubmissionResult> future = completionService.submit(task);
            pendingCount++;
            return future;
        }

        public Future<SubmissionResult> poll() {
            final Future<SubmissionResult> future = completionService.poll();
            if (future != null)
                pendingCount--;
            return future;
        }

        public Future<SubmissionResult> take() throws InterruptedException {
            final Future<SubmissionResult> future = completionService.take();
            pendingCount--;
            return future;
        }

        public int getPendingCount() {
            return pendingCount;
        }
    }
}
