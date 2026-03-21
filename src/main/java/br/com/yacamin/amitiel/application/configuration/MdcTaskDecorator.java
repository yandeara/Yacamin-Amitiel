package br.com.yacamin.amitiel.application.configuration;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propaga MDC (Mapped Diagnostic Context) do thread caller para o thread worker.
 * Custo: ~20ns por task (HashMap copy + set/clear).
 * Garante que logs emitidos em threads de pool carregam o contexto do evento original.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
