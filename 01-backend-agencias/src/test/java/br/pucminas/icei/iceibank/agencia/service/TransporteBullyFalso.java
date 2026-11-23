package br.pucminas.icei.iceibank.agencia.service;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias.Agencia;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Simula as outras agencias em memoria: cada uma esta "no ar" ou "fora do ar". */
class TransporteBullyFalso implements TransporteBully {

    private final Set<Integer> vivas = new HashSet<>();
    private final List<Integer> eleicoesEnviadas = new ArrayList<>();
    private final List<Integer> coordenadoresEnviados = new ArrayList<>();
    private final List<Integer> lideresConsultados = new ArrayList<>();

    synchronized TransporteBullyFalso vivas(Integer... ids) {
        vivas.clear();
        vivas.addAll(List.of(ids));
        return this;
    }

    @Override
    public synchronized boolean enviarEleicao(Agencia destino, int origemId, int[] vetorEnvio) {
        eleicoesEnviadas.add(destino.id());
        return vivas.contains(destino.id());
    }

    @Override
    public synchronized boolean enviarCoordenador(Agencia destino, int liderId, int[] vetorEnvio) {
        coordenadoresEnviados.add(destino.id());
        return vivas.contains(destino.id());
    }

    @Override
    public synchronized boolean consultarLider(Agencia destino) {
        lideresConsultados.add(destino.id());
        return vivas.contains(destino.id());
    }

    synchronized List<Integer> eleicoesEnviadas() {
        return List.copyOf(eleicoesEnviadas);
    }

    synchronized List<Integer> coordenadoresEnviados() {
        return List.copyOf(coordenadoresEnviados);
    }

    synchronized List<Integer> lideresConsultados() {
        return List.copyOf(lideresConsultados);
    }
}
