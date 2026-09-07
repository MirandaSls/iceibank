package br.pucminas.icei.iceibank.agencia.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Libera o acesso do frontend (servido em outra porta) a API da agencia.
 *
 * <p>O filtro de CORS roda ANTES do filtro de JWT de proposito: assim ate as respostas 401
 * chegam ao navegador com os cabecalhos de CORS e o frontend consegue exibir a mensagem de
 * erro real, em vez de um erro generico de CORS.
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration configuracao = new CorsConfiguration();
        configuracao.addAllowedOriginPattern("*");
        configuracao.addAllowedHeader("*");
        configuracao.addAllowedMethod("*");
        // sem isto o JavaScript nao consegue ler o cabecalho de resposta da idempotencia
        configuracao.addExposedHeader("Idempotency-Replayed");

        UrlBasedCorsConfigurationSource origem = new UrlBasedCorsConfigurationSource();
        origem.registerCorsConfiguration("/**", configuracao);

        FilterRegistrationBean<CorsFilter> registro = new FilterRegistrationBean<>(new CorsFilter(origem));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }
}
