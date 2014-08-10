package org.apache.stratos.cep.extension;

/**
 * Created by asiri on 8/9/14.
 */

import org.wso2.siddhi.core.config.SiddhiContext;
import org.wso2.siddhi.core.executor.function.FunctionExecutor;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.extension.annotation.SiddhiExtension;

@SiddhiExtension(namespace = "stratos", function = "divider")
public class MemeberRequestHandlingCapabilityWindowProcessor extends FunctionExecutor {

    Attribute.Type returnType;

    @Override
    public void init(Attribute.Type[] types, SiddhiContext siddhiContext) {
    }

    @Override
    protected Object process(Object obj) {

        double[] value = new double[2];
        if (obj instanceof Object[]) {
            int i=0;
            for (Object aObj : (Object[]) obj) {
                value[i]= Double.parseDouble(String.valueOf(aObj));
                i++;
            }
        }
        return value[0]/value[1];
    }

    @Override
    public void destroy() {

    }

    @Override
    public Attribute.Type getReturnType() {
        return null;
    }
}
