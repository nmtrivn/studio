package com.samsung.sds.brightics.server.common.util.keras;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import com.samsung.sds.brightics.common.core.exception.BrighticsCoreException;
import com.samsung.sds.brightics.server.common.util.keras.model.KerasParameterConstant;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonObject;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowDataDLLoadNode;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowDataNode;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowLayerNode;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowNode;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowOutputNode;
import com.samsung.sds.brightics.server.common.util.keras.flow.KerasFlowModelNode;
import com.samsung.sds.brightics.server.common.util.keras.model.KerasLayers;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class KerasScriptUtil {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final String LOG_DIR = "/log/";
    private static final String MODULES_DIR = "/modules/";
    private static final String CHECKPOINT_DIR = "/checkpoint/";

    public static String getDLHomePath() {
        String dlhome = "../../dl";
        Config configObject = ConfigFactory.load();
        try {
            if (configObject.getString("brightics.dl.repo.path") != null) {
                dlhome = configObject.getString("brightics.dl.repo.path");
            }
        } catch (Exception e) {
            return dlhome;
        }

        return dlhome;
    }

    public static String getKerasPredictScript(String outDFAlias, JsonObject param) {
        if (PythonScriptUtil.isJsonElementBlank(param.get("inputDataPath"))) {
            throw new BrighticsCoreException("3109", "Input Data Path");
        }

        if (PythonScriptUtil.isJsonElementBlank(param.get("checkpointPath"))) {
            throw new BrighticsCoreException("3109", "Checkpoint");
        }

        String checkpointPath = param.get("checkpointPath").getAsString();
        String dataPath = param.get("inputDataPath").getAsString();

        String batchSize = StringUtils.EMPTY;

        if (!PythonScriptUtil.isJsonElementBlank(param.get("batch_size"))) {
            batchSize = ", batch_size=" + param.get("batch_size").getAsString();
        }

        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        script.add("from keras.models import load_model");
        script.add(StringUtils.EMPTY);
        script.add(String.format("model = load_model(\"\"\"%s\"\"\")", makeDlAbsolutePath(checkpointPath)));
        script.add("predict_data = read_parquet(\"\"\"" + dataPath + "\"\"\").values");
        script.add(StringUtils.EMPTY);
        script.add(String.format("predict_result = model.predict(predict_data%s)", batchSize));
        script.add(StringUtils.EMPTY);
        script.add("result_data = [tuple(float(v) for v in p) for p in predict_result]");
        script.add("result_column = ['predict_' + str(i) for i in range(len(result_data[0]))]");
        script.add(StringUtils.EMPTY);
        script.add(String.format("%s = pd.DataFrame(result_data, columns=result_column)", outDFAlias));

        return script.toString();
    }

    private static String makeDlAbsolutePath(String dlRelativePath) {
        return Paths.get(getDLHomePath(), dlRelativePath).toString();
    }

    public static String makeLayersImportScript(Set<KerasLayers> layers) {
        return layers.stream()
                .collect(Collectors.groupingBy(KerasLayers::getModuleName)).entrySet()
                .stream()
                .map(m -> {
                    String classesString = m.getValue().stream()
                            .map(KerasLayers::getClassName)
                            .collect(Collectors.joining(", "));

                    return String.format("from %s import %s", m.getKey(), classesString);
                }).collect(Collectors.joining(LINE_SEPARATOR));
    }

    public static String makeSequentialModelScript(KerasModelFlow modelFlowData) {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        KerasFlowNode node = modelFlowData.getInputNodes().get(0);

        while (node != null && !node.isLastNode()) {
            if (node instanceof KerasFlowLayerNode) {
                KerasFlowLayerNode layerNode = (KerasFlowLayerNode) node;

                if (layerNode.isFirstLayer()) {
                    script.add("model = Sequential()");
                    script.add(String.format("model.add(%s)", layerNode.getLayerScript(true)));
                } else {
                    script.add(String.format("model.add(%s)", layerNode.getLayerScript()));
                }
            } else {
                script.add(((KerasFlowModelNode) node).getScript());
            }

            node = node.getNextNodes().get(0);
        }

        return script.toString();
    }

    public static String makeFunctionalModelScript(KerasModelFlow modelFlowData) throws Exception {
        return makeFunctionalModelScript(modelFlowData, "");
    }

    public static String makeFunctionalModelScript(KerasModelFlow modelFlowData, String indent) throws Exception {
        StringBuffer script = new StringBuffer();

        Set<String> visited = new HashSet<>();
        Stack<KerasFlowNode> stack = new Stack<>();
        stack.addAll(modelFlowData.getInputNodes());

        while (!stack.isEmpty()) {
            KerasFlowNode node = stack.pop();

            while (node != null && !node.isLastNode()) {
                if (node.getPrevNodes().size() > 1) {
                    node = findNotVisitedPrevNode(node, visited);
                }

                if (visited.contains(node.getFid())) break;

                visited.add(node.getFid());

                if (node instanceof KerasFlowModelNode) {
                    KerasFlowModelNode modelNode = (KerasFlowModelNode) node;
                    if (modelNode.hasScript()) {
                        script.append(modelNode.getScript(indent)).append(LINE_SEPARATOR);
                    }

                    if (node instanceof KerasFlowLayerNode) {
                        KerasFlowLayerNode layerNode = (KerasFlowLayerNode) node;

                        if (layerNode.isFirstLayer()) {
                            script.append(LINE_SEPARATOR);

                            String inputLayerName = layerNode.getInputLayerName();

                            if (layerNode.isApplications()) {
                                script.append(indent).append(String.format("%s = %s", inputLayerName, layerNode.getLayerScript(true))).append(LINE_SEPARATOR);
                            } else {
                                script.append(indent).append(String.format("%s = Input(shape=%s, name=\"\"\"%s\"\"\")", inputLayerName, layerNode.getInputShape(), inputLayerName)).append(LINE_SEPARATOR);
                                script.append(indent).append(String.format("%s = %s(%s)", layerNode.getVariableName(), layerNode.getLayerScript(), layerNode.getPrevVariableName())).append(LINE_SEPARATOR);
                            }

                            stack.addAll(getIndexedNextNodes(layerNode.getDataNode().getNextNodes(), layerNode));
                        } else {
                            if (layerNode.getNodeIndex() > 1) {
                                script.append(LINE_SEPARATOR);
                            }

                            script.append(indent).append(String.format("%s = %s(%s)", layerNode.getVariableName(), layerNode.getLayerScript(), layerNode.getPrevVariableName())).append(LINE_SEPARATOR);
                        }
                    }
                }

                List<KerasFlowNode> nextNodes = node.getNextNodes();
                node = nextNodes.get(0);
                stack.addAll(getIndexedNextNodes(nextNodes, node));
            }
        }

        script.append(LINE_SEPARATOR);
        script.append(indent).append(makeModelDefineScript(modelFlowData.getOutputNodes()));

        return script.toString();
    }

    private static KerasFlowNode findNotVisitedPrevNode(KerasFlowNode node, Set<String> visited) {
        for (KerasFlowNode prev : node.getPrevNodes()) {
            if (!visited.contains(prev.getFid()) && prev instanceof KerasFlowModelNode) return findNotVisitedPrevNode(prev, visited);
        }
        return node;
    }

    private static List<KerasFlowNode> getIndexedNextNodes(List<KerasFlowNode> nextNodes, KerasFlowNode exclude) {
        List<KerasFlowNode> nodes = new ArrayList<>();
        if (nextNodes.size() > 1) {
            nodes.addAll(nextNodes);
            nodes.remove(exclude);
        }

        for (int i = 0; i < nodes.size(); i++) {
            KerasFlowNode nextNode = nodes.get(i);
            if (nextNode instanceof KerasFlowLayerNode) {
                ((KerasFlowLayerNode) nextNode).setNodeIndex(i + 2);
            }
        }

        return nodes;
    }

    private static String makeModelDefineScript(List<KerasFlowOutputNode> outputNodes) {
        StringJoiner inputJoiner = new StringJoiner(", ");
        StringJoiner outputJoiner = new StringJoiner(", ");
        for (KerasFlowOutputNode node : outputNodes) {
            KerasFlowDataNode dataNode = node.getTrainDataNode();

            if (isFirstLayerApplicationsType(dataNode)) {
                inputJoiner.add(dataNode.getInputLayerName() + ".input");
            } else {
                inputJoiner.add(dataNode.getInputLayerName());
            }
            outputJoiner.add(dataNode.getOutputLayerName());
        }

        String inputs = inputJoiner.toString();
        String outputs = outputJoiner.toString();

        if (outputNodes.size() > 1) {
            inputs = String.format("[%s]", inputs);
            outputs = String.format("[%s]", outputs);
        }

        return String.format("model = Model(inputs=%s, outputs=%s)", inputs, outputs);
    }

    private static boolean isFirstLayerApplicationsType(KerasFlowDataNode dataNode) {
        for (KerasFlowNode node : dataNode.getNextNodes()) {
            if (node instanceof KerasFlowLayerNode) {
                KerasFlowLayerNode layerNode = (KerasFlowLayerNode) node;

                if (layerNode.isFirstLayer() && layerNode.isApplications()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String makeTrainDataLoadScript(List<KerasFlowDataNode> dataNodes, boolean useOnlyFileName) throws Exception {
        StringJoiner dataScript = new StringJoiner(LINE_SEPARATOR);

        String importScript = dataNodes.stream().map(KerasFlowDataNode::importScript).distinct().collect(Collectors.joining(LINE_SEPARATOR));
        if (StringUtils.isNotBlank(importScript)) {
            dataScript.add(importScript).add(StringUtils.EMPTY);
        }

        for (int i = 0; i < dataNodes.size(); i++) {
            if (i != 0) {
                dataScript.add(StringUtils.EMPTY);
            }

            dataScript.add(dataNodes.get(i).script(useOnlyFileName));
        }

        return dataScript.toString();
    }

    public static String makeModelCompileScript(JsonObject args) throws Exception {
        return makeModelCompileScript(args, "");
    }

    public static String makeModelCompileScript(JsonObject args, String indent) throws Exception {
        return String.format("%smodel.compile(%s)",
                indent,
                PythonScriptUtil.makePythonArgumentsString(
                        Arrays.asList(
                                KerasParameterConstant.OPTIMIZER, KerasParameterConstant.LOSS)
                        , Collections.singletonList(KerasParameterConstant.METRICS)
                        , args));
    }

    public static String makeModelFitScript(JsonObject args, List<KerasFlowOutputNode> outputNodes) throws Exception {
        return makeModelFitScript(args, outputNodes, "");
    }

    public static String makeModelFitScript(JsonObject args, List<KerasFlowOutputNode> outputNodes, String indent) throws Exception {
        String dataString = makeModelFitDataDictionaryString(outputNodes);
        String arguments = makeModelFitArgumentsString(args);

        return String.format("%smodel.fit(%s%s)", indent, dataString, arguments);
    }

    public static String makeModelFitScriptWithCallbacks(JsonObject args, List<KerasFlowOutputNode> outputNodes, List<String> callbacks) throws Exception {
        String dataString = makeModelFitDataDictionaryString(outputNodes);
        String arguments = makeModelFitArgumentsString(args);
        String callbacksString = callbacks.stream().collect(Collectors.joining(", "));

        return String.format("model.fit(%s%s, callbacks=[%s], verbose=0)", dataString, arguments, callbacksString);
    }

    public static String makeModelFitGeneratorScript(JsonObject args, List<KerasFlowOutputNode> outputNodes) throws Exception {
        String arguments = makeModelFitGeneratorArgumentsString(args);
        String dataString = outputNodes.get(0).getTrainDataNode().getDataVariableName();

        KerasFlowDataNode validationNode = outputNodes.get(0).getValidationDataNode();

        if (validationNode != null) {
            return String.format("model.fit_generator(%s, validation_data=%s%s)", dataString, validationNode.getDataVariableName(), arguments);
        } else {
            return String.format("model.fit_generator(%s%s)", dataString, arguments);
        }
    }

    public static String makeModelFitGeneratorScriptWithCallbacks(JsonObject args, List<KerasFlowOutputNode> outputNodes, List<String> callbacks) throws Exception {
        String arguments = makeModelFitGeneratorArgumentsString(args);
        String dataString = outputNodes.get(0).getTrainDataNode().getDataVariableName();
        String callbacksString = callbacks.stream().collect(Collectors.joining(", "));

        KerasFlowDataNode validationNode = outputNodes.get(0).getValidationDataNode();

        if (validationNode != null) {
            return String.format("model.fit_generator(%s, validation_data=%s%s, callbacks=[%s], verbose=0)", dataString, validationNode.getDataVariableName(), arguments, callbacksString);
        } else {
            return String.format("model.fit_generator(%s%s, callbacks=[%s], verbose=0)", dataString, arguments, callbacksString);
        }
    }

    private static String makeModelFitDataDictionaryString(List<KerasFlowOutputNode> outputNodes) {
        StringJoiner inputJoiner = new StringJoiner(", ");
        StringJoiner outputJoiner = new StringJoiner(", ");

        for (KerasFlowOutputNode outputNode : outputNodes) {
            KerasFlowDataDLLoadNode dataNode = (KerasFlowDataDLLoadNode) outputNode.getTrainDataNode();

            inputJoiner.add(String.format("\"\"\"%s\"\"\": %s", dataNode.getInputLayerName(), dataNode.getDataVariableName()));
            outputJoiner.add(String.format("\"\"\"%s\"\"\": %s", dataNode.getOutputLayerName(), dataNode.getLabelVariableName()));
        }

        String inputDatas = String.format("{%s}", inputJoiner.toString());
        String outputDatas = String.format("{%s}", outputJoiner.toString());

        return inputDatas + ", " + outputDatas;
    }

    private static String makeModelFitArgumentsString(JsonObject args) throws Exception {
        @SuppressWarnings("unchecked")
        String arguments = PythonScriptUtil.makePythonArgumentsString(
                ListUtils.EMPTY_LIST
                , Arrays.asList(KerasParameterConstant.BATCH_SIZE, KerasParameterConstant.EPOCHS)
                , args);

        if (StringUtils.isNotBlank(arguments)) {
            arguments = ", " + arguments;
        }

        return arguments;
    }

    private static String makeModelFitGeneratorArgumentsString(JsonObject args) throws Exception {
        @SuppressWarnings("unchecked")
        String arguments = PythonScriptUtil.makePythonArgumentsString(
                ListUtils.EMPTY_LIST
                , Arrays.asList(KerasParameterConstant.STEPS_PER_EPOCH, KerasParameterConstant.EPOCHS, KerasParameterConstant.VALIDATION_STEPS)
                , args);

        if (StringUtils.isNotBlank(arguments)) {
            arguments = ", " + arguments;
        }

        return arguments;
    }

    public static String makeAddSystemPathBrighticsModulesDir() {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        script.add("import sys");
        script.add(String.format("sys.path.append(\"\"\"%s\"\"\")", getDLHomePath() + MODULES_DIR));
        script.add(StringUtils.EMPTY);

        return script.toString();
    }

    public static String makeBrighticsLoggerCallbackScript(String loggerVariableName, String jid) {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        String logPath = getDLHomePath() + LOG_DIR + jid + ".log";

        script.add("from brightics_keras_logger import BrighticsLogger");
        script.add(String.format("%s = BrighticsLogger(\"\"\"%s\"\"\")", loggerVariableName, logPath));
        script.add(StringUtils.EMPTY);

        return script.toString();
    }

    public static String makeCheckpointLoggerCallbackScript(String ckptVariableName, String userId, String ckptName, String metrics) {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        String checkpointFileName = String.format("%s-epoch_{epoch:02d}-loss_{loss:.2f}-%s_{%s:.2f}.hdf5", ckptName, metrics, getMetricsLogsName(metrics));

        script.add("from brightics_deeplearning_util import make_checkpoint_dir");
        script.add(String.format("created_checkpoint_dir = make_checkpoint_dir(\"\"\"%s\"\"\", \"\"\"%s\"\"\", \"\"\"%s\"\"\")", getDLHomePath() + CHECKPOINT_DIR, userId, ckptName));
        script.add(StringUtils.EMPTY);
        script.add("from keras.callbacks import ModelCheckpoint");
        script.add(String.format("%s = ModelCheckpoint(filepath = created_checkpoint_dir + \"\"\"/%s\"\"\")", ckptVariableName, checkpointFileName));
        script.add(StringUtils.EMPTY);

        return script.toString();
    }

    /**
     * @param metrics
     * @return
     * accuracy: acc
     * categorical_accuracy: categorical_accuracy
     * binary_accuracy: binary_accuracy
     */
    private static String getMetricsLogsName(String metrics) {
        if ("accuracy".equalsIgnoreCase(metrics)) return "acc";
        else return metrics;
    }

    public static String makeModelSummaryWriteScript(String jid) {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        String summaryPath = getDLHomePath() + LOG_DIR + jid + ".summary";

        script.add("summary_log = []");
        script.add("model.summary(line_length=200, print_fn=summary_log.append)");
        script.add(StringUtils.EMPTY);
        script.add(makeAddSystemPathBrighticsModulesDir());
        script.add("from brightics_deeplearning_util import write_summary");
        script.add(String.format("write_summary(\"\"\"%s\"\"\", summary_log)", summaryPath));

        return script.toString();
    }


    public static String makeOPTScript(String script0) {
        StringJoiner script = new StringJoiner(LINE_SEPARATOR);

        script.add("from hyperopt import Trials, STATUS_OK, tpe");
        script.add("from hyperas import optim");
        script.add("from hyperas.distributions import choice, uniform");
        script.add(script0);
        script.add("best_run, best_model = optim.minimize(model, data=data, algo=tpe.suggest, max_evals=5, trials=Trials())");
        script.add("    X_train, Y_train, X_train, Y_train = data()");
        return script.toString();
    }

}
