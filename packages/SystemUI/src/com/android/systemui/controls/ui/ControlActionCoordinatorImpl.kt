/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.annotation.AnyThread
import android.annotation.MainThread
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.VibrationEffect
import android.service.controls.Control
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.FloatAction
import android.util.Log
import android.view.HapticFeedbackConstants
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.globalactions.GlobalActionsComponent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.wm.shell.TaskViewFactory
import java.util.Optional
import javax.inject.Inject

@SysUISingleton
class ControlActionCoordinatorImpl @Inject constructor(
        private val context: Context,
        private val bgExecutor: DelayableExecutor,
        @Main private val uiExecutor: DelayableExecutor,
        private val activityStarter: ActivityStarter,
        private val broadcastSender: BroadcastSender,
        private val keyguardStateController: KeyguardStateController,
        private val taskViewFactory: Optional<TaskViewFactory>,
        private val controlsMetricsLogger: ControlsMetricsLogger,
        private val vibrator: VibratorHelper,
        private val controlsSettingsRepository: ControlsSettingsRepository,
        private val controlsSettingsDialogManager: ControlsSettingsDialogManager,
        private val featureFlags: FeatureFlags,
        private val globalActionsComponent: GlobalActionsComponent,
) : ControlActionCoordinator {
    private var dialog: Dialog? = null
    private var pendingAction: Action? = null
    private var actionsInProgress = mutableSetOf<String>()
    private val isLocked: Boolean
        get() = !keyguardStateController.isUnlocked()
    private val allowTrivialControls: Boolean
        get() = controlsSettingsRepository.allowActionOnTrivialControlsInLockscreen.value
    override var activityContext: Context? = null

    companion object {
        private const val RESPONSE_TIMEOUT_IN_MILLIS = 3000L
    }

    override fun closeDialogs() {
        if (!featureFlags.isEnabled(Flags.USE_APP_PANELS)) {
            controlsSettingsDialogManager.closeDialog()
        }
        val isActivityFinishing =
            (activityContext as? Activity)?.let { it.isFinishing || it.isDestroyed }
        if (isActivityFinishing == true) {
            dialog = null
            return
        }
        if (dialog?.isShowing == true) {
            dialog?.dismiss()
            dialog = null
        }
    }

    override fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean) {
        controlsMetricsLogger.touch(cvh, isLocked)
        bouncerOrRun(
            createAction(
                cvh.cws.ci.controlId,
                {
                    cvh.layout.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    cvh.action(BooleanAction(templateId, !isChecked))
                },
                true /* blockable */,
                cvh.cws.control?.isAuthRequired ?: true /* authIsRequired */
            )
        )
    }

    override fun touch(cvh: ControlViewHolder, templateId: String, control: Control) {
        controlsMetricsLogger.touch(cvh, isLocked)
        val blockable = cvh.usePanel()
        bouncerOrRun(
            createAction(
                cvh.cws.ci.controlId,
                {
                    cvh.layout.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (cvh.usePanel()) {
                        showDetail(cvh, control.getAppIntent())
                    } else {
                        cvh.action(CommandAction(templateId))
                    }
                },
                blockable /* blockable */,
                cvh.cws.control?.isAuthRequired ?: true /* authIsRequired */
            )
        )
    }

    override fun drag(isEdge: Boolean) {
        if (isEdge) {
            vibrate(Vibrations.rangeEdgeEffect)
        } else {
            vibrate(Vibrations.rangeMiddleEffect)
        }
    }

    override fun setValue(cvh: ControlViewHolder, templateId: String, newValue: Float) {
        controlsMetricsLogger.drag(cvh, isLocked)
        bouncerOrRun(
            createAction(
                cvh.cws.ci.controlId,
                { cvh.action(FloatAction(templateId, newValue)) },
                false /* blockable */,
                cvh.cws.control?.isAuthRequired ?: true /* authIsRequired */
            )
        )
    }

    override fun longPress(cvh: ControlViewHolder) {
        controlsMetricsLogger.longPress(cvh, isLocked)
        bouncerOrRun(
            createAction(
                cvh.cws.ci.controlId,
                {
                    // Long press snould only be called when there is valid control state,
                    // otherwise ignore
                    cvh.cws.control?.let {
                        cvh.layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showDetail(cvh, it.getAppIntent())
                    }
                },
                false /* blockable */,
                cvh.cws.control?.isAuthRequired ?: true /* authIsRequired */
            )
        )
    }

    override fun runPendingAction(controlId: String) {
        if (isLocked) return
        if (pendingAction?.controlId == controlId) {
            showSettingsDialogIfNeeded(pendingAction!!)
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    @MainThread
    override fun enableActionOnTouch(controlId: String) {
        actionsInProgress.remove(controlId)
    }

    private fun shouldRunAction(controlId: String) =
        if (actionsInProgress.add(controlId)) {
            uiExecutor.executeDelayed({
                actionsInProgress.remove(controlId)
            }, RESPONSE_TIMEOUT_IN_MILLIS)
            true
        } else {
            false
        }

    @AnyThread
    @VisibleForTesting
    fun bouncerOrRun(action: Action) {
        val authRequired = action.authIsRequired || !allowTrivialControls

        if (keyguardStateController.isShowing() && authRequired) {
            if (activityContext == null) {
                broadcastSender.closeSystemDialogs()

                // pending actions will only run after the control state has been refreshed
                pendingAction = action
            }
            activityStarter.dismissKeyguardThenExecute({
                Log.d(ControlsUiController.TAG, "Device unlocked, invoking controls action")
                if (activityContext == null) {
                    globalActionsComponent.handleShowGlobalActionsMenu()
                } else {
                    action.invoke()
                }
                true
            }, { pendingAction = null }, true /* afterKeyguardGone */)
        } else {
            showSettingsDialogIfNeeded(action)
            action.invoke()
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        vibrator.vibrate(effect)
    }

    private fun showDetail(cvh: ControlViewHolder, pendingIntent: PendingIntent) {
        bgExecutor.execute {
            val activities: List<ResolveInfo> = context.packageManager.queryIntentActivities(
                pendingIntent.getIntent(),
                PackageManager.MATCH_DEFAULT_ONLY
            )

            uiExecutor.execute {
                // make sure the intent is valid before attempting to open the dialog
                if (activities.isNotEmpty() && taskViewFactory.isPresent) {
                    if (activityContext == null) { // activityContext == null means we are in global actions
                        broadcastSender.closeSystemDialogs()
                        if (keyguardStateController.isShowing()) {
                            activityStarter.dismissKeyguardThenExecute({
                                pendingIntent.send()
                                true
                            }, {}, true /* afterKeyguardGone */)
                        } else {
                            pendingIntent.send()
                        }
                        return@execute
                    }
                    taskViewFactory.get().create(context, uiExecutor, {
                        dialog = DetailDialog(
                            activityContext!!, broadcastSender,
                            it, pendingIntent, cvh, keyguardStateController, activityStarter
                        ).also {
                            it.setOnDismissListener { _ -> dialog = null }
                            it.show()
                        }
                    })
                } else {
                    cvh.setErrorStatus()
                }
            }
        }
    }

    private fun showSettingsDialogIfNeeded(action: Action) {
        if (action.authIsRequired || activityContext == null) {
            return
        }
        if (!featureFlags.isEnabled(Flags.USE_APP_PANELS)) {
            controlsSettingsDialogManager.maybeShowDialog(activityContext!!) {}
        }
    }

    @VisibleForTesting
    fun createAction(
        controlId: String,
        f: () -> Unit,
        blockable: Boolean,
        authIsRequired: Boolean
    ) = Action(controlId, f, blockable, authIsRequired)

    inner class Action(
        val controlId: String,
        val f: () -> Unit,
        val blockable: Boolean,
        val authIsRequired: Boolean
    ) {
        fun invoke() {
            if (!blockable || shouldRunAction(controlId)) {
                f.invoke()
            }
        }
    }
}
