package io.jenkins.plugins.restrictsteps

import com.cloudbees.groovy.cps.Outcome
import hudson.Extension
import hudson.ExtensionList
import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.model.CauseOfInterruption
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.flow.StepListener
import org.jenkinsci.plugins.workflow.libs.LibraryAdder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext

import edu.umd.cs.findbugs.annotations.NonNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

// GraphListener additions
import org.jenkinsci.plugins.workflow.flow.GraphListener
import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.actions.TimingAction
import org.jenkinsci.plugins.workflow.support.actions.LogStorageAction
import org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction
import org.jenkinsci.plugins.workflow.actions.LabelAction

@Extension
class RestrictStepsListener implements GraphListener, StepListener {
    private class BlockedStepCause extends CauseOfInterruption {
        private String message
        BlockedStepCause(String message) {
            this.message = message
        }
        @Override public String getShortDescription() {
            return this.message;
        }
    }
    // ID within config file provider plugin to get YAML content
    private static String config_id = 'restricted-steps'
    private static final Logger logger = Logger.getLogger(RestrictStepsListener.class.getName());
    private transient ConcurrentHashMap restricted
    private transient String config
    private transient GlobalConfigFiles configFiles
    private transient Yaml yamlParser

    synchronized Map resolveRestricted() {
        if(!this.restricted) {
            this.restricted = new ConcurrentHashMap()
        }
        if(!this.configFiles) {
            this.configFiles = Jenkins.instance.getExtensionList(GlobalConfigFiles).get(GlobalConfigFiles)
        }
        if(!this.yamlParser) {
            this.yamlParser = new Yaml(new SafeConstructor(new LoaderOptions()))
        }
        String content = configFiles.getById(this.config_id)?.content
        if(content) {
            this.restricted.clear()
            def parsed = yamlParser.load(content)
            if(!(parsed in Map) || !(parse?.steps in List)) {
                // TODO severe log
                return
            }
            parsed.each { k, v ->
                this.restricted[k] = v
            }
        }

        Collections.unmodifiableMap(this.restricted ?: [:])
    }

    /**
      Search pipeline steps

      https://github.com/jenkinsci/workflow-cps-plugin/blob/a9795f98880c8fab08a904c1620bdf27f96faa8b/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/DSL.java#L312-L326
      */
    @Override
    void notifyOfNewStep(@NonNull Step step, @NonNull StepContext context) {
        String stepName = step.getDescriptor().getFunctionName()
        final Run run = context.get(Run)
        final CpsFlowDefinition flowDefinition = context.get(CpsFlowDefinition)
        TaskListener listener = context.get(TaskListener)
        logger.fine("""\
            Running step: ${step.class}
            step class: ${stepName}
            sandbox: ${flowDefinition?.isSandbox() ?: false}
            config: ${resolveRestricted()}
            """.stripIndent().trim())
        Map deny = resolveRestricted()
        if(!deny) {
            return
        }
        // notify context to abort job
        if(stepName in deny.steps) {
            FlowInterruptedException cause = new FlowInterruptedException(Result.FAILURE, new BlockedStepCause("ERROR: ${stepName} step not allowed via restricted steps by admin."));
            context.completed(new Outcome(null, cause))
        }
    }

    /**
       Search graph events.
     */
    @Override
    void onNewHead(FlowNode node) {
        ArgumentsActionImpl args = node.getAction(ArgumentsActionImpl)
        logger.finest("""\
            FLOWNODE
                displayName ${node?.getDisplayName()}
                displayFunctionName ${node?.getDisplayFunctionName()}
                sandbox ${node?.execution?.isSandbox()}
                parent sandbox ${node.parents*.execution*.isSandbox().inspect()}
                typeDisplayName ${node?.getTypeDisplayName()}
                typeFunctionName ${node?.getTypeFunctionName()}
            TimingAction
                displayName ${node.getAction(TimingAction)?.getDisplayName()}
            LogStorageAction
                displayName ${node.getAction(LogStorageAction)?.getDisplayName()}
            ArgumentsActionImpl
                argumentsInternal ${args?.getArgumentsInternal()}
                displayName ${args?.getDisplayName()}
                stepArgumentsAsString ${args?.getStepArgumentsAsString()}
            BodyInvocationAction
                displayName ${node.getAction(BodyInvocationAction)?.getDisplayName()}
            LabelAction
                displayName ${node.getAction(LabelAction)?.getDisplayName()}
            Actions
                ${node.actions.join('\n    ')}
            """.stripIndent().trim())

        /* no good; execution already starts by the time this point is reached.
        Map deny = resolveRestricted()
        if(!(deny?.steps in List)) {
            return
        }
        String stepName = node.typeFunctionName
        if(stepName in deny.steps) {
            BlockedStepCause cause = new BlockedStepCause("ERROR: ${stepName} step not allowed via restricted steps by admin.")
            node.execution.interrupt(Result.FAILURE, cause)
        }
        */
    }

}
