package br.univali.portugol.nucleo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

import br.univali.portugol.nucleo.analise.ResultadoAnalise;
import br.univali.portugol.nucleo.asa.ASAPrograma;
import br.univali.portugol.nucleo.asa.TipoDado;
import br.univali.portugol.nucleo.bibliotecas.base.Biblioteca;
import br.univali.portugol.nucleo.bibliotecas.base.ErroExecucaoBiblioteca;
import br.univali.portugol.nucleo.execucao.ModoEncerramento;
import br.univali.portugol.nucleo.execucao.ObservadorExecucao;
import br.univali.portugol.nucleo.execucao.ResultadoExecucao;
import br.univali.portugol.nucleo.execucao.TradutorRuntimeException;
import br.univali.portugol.nucleo.execucao.erros.mensagens.ErroEstouroPilha;
import br.univali.portugol.nucleo.execucao.erros.mensagens.ErroExecucaoNaoTratado;
import br.univali.portugol.nucleo.execucao.erros.mensagens.ErroMemoriaInsuficiente;
import br.univali.portugol.nucleo.execucao.erros.mensagens.ErroValorEntradaInvalido;
import br.univali.portugol.nucleo.execucao.erros.tradutores.TradutorArithmeticException;
import br.univali.portugol.nucleo.execucao.erros.tradutores.TradutorArrayIndexOutOfBoundsException;
import br.univali.portugol.nucleo.execucao.es.Armazenador;
import br.univali.portugol.nucleo.execucao.es.Entrada;
import br.univali.portugol.nucleo.execucao.es.EntradaSaidaPadrao;
import br.univali.portugol.nucleo.execucao.es.InputMediator;
import br.univali.portugol.nucleo.execucao.es.Saida;
import br.univali.portugol.nucleo.mensagens.ErroExecucao;

/**
 * Esta classe provê uma fachada (Facade) para abstrair os detalhes da execução
 * dos programas que não interessam aos utilizadores do Portugol.
 * <p>
 * Ela se encarrega de instanciar um interpretador para o código fonte e de
 * gerenciar o ciclo de vida (inicio, fim, interrupção) da Thread na qual o
 * interpretador irá executar.
 *
 *
 * @author Luiz Fernando Noschang
 * @author Fillipi Domingos Pelz
 * @author Elieser A. de Jesus
 *
 * @version 1.0
 * @see Interpretador
 * @see Thread
 */
public abstract class Programa
{
	/*
	 * Optei por criar manualmente o pool de threads ao invés de usar um método
	 * da classe Executors.
	 * 
	 * O pool criado aqui é idêntico ao criado pelo método
	 * newCachedThreadPool(), exceto pela propriedade keepAliveTime. Esta
	 * propriedade define quanto tempo a thread pode ficar inativa (sem ter uma
	 * tarefa submetida) antes de ser desalocada da memória.
	 *
	 * Na implementação da classe Executors, o keepAliveTime padrão é de 60
	 * segundos, um tempo consideravelmente pequeno. Ao analisar a execução com
	 * o JVisualVM, foi possível perceber que com frequência, as threads que
	 * estavam aguardando tarefas no pool eram desalocadas e novas threads
	 * tinham que ser criadas.
	 * 
	 * Nesta implementação, o tempo foi aumentado (exageradamente) para 2 horas.
	 */
	private static final ExecutorService POOL_DE_THREADS = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 2L, TimeUnit.HOURS, new SynchronousQueue<Runnable>(), new NamedThreadFactory("Portugol Núcleo (Thread de programa #%d", Thread.MAX_PRIORITY));

	private static final Map<Class<? extends RuntimeException>, TradutorRuntimeException<? extends RuntimeException>> tradutoresRuntimeException = new HashMap<>();

	private Saida saida;
	private Entrada entrada;
	private String funcaoInicial;
	private File diretorioTrabalho = new File(".");
	private File arquivoOrigem = null;

	private TarefaExecucao tarefaExecucao = null;

	private ASAPrograma arvoreSintaticaAbstrataPrograma;
	private List<String> funcoes;
	private ResultadoAnalise resultadoAnalise;

	private final ArrayList<ObservadorExecucao> observadores;

	private boolean[] pontosDeParadaAtivados = new boolean[0];

	private volatile boolean lendo = false;
	private volatile boolean leituraIgnorada = false;

	protected volatile boolean interrupcaoSolicitada = false;
	private Future controleExecucao;

	private final Object LOCK = new Object();

	private int ultimaLinha = 0;
	private int ultimaColuna = 0;

	public static final Object OBJETO_NULO = new Object(); // usando como valor
															// inicial para as
															// variáveis
															// inspecionadas

	public static enum Estado
	{
		BREAK_POINT, // usuário clicou no botão que executa o programa até
						// atingir um ponto de parada, caso exista algum
		STEP_INTO, // não utilizado no momento
		STEP_OVER, // executa passo a passo, para em todos os nós que são
					// paráveis (nem todos são)
		PARADO// esperando o usuário iniciar a execução
	}

	private Estado estado = Estado.PARADO;

	/***
	 * Classe usada apenas internamente para armazenar os dados dos vetores que
	 * são inspecionados durante a execução
	 */
	protected class Vetor
	{
		private final Object dados[];
		private int ultimaColunaAlterada = -1;
		public final int tamanho;

		protected Vetor(int tamanho)
		{
			dados = new Object[tamanho];
			this.tamanho = tamanho;
		}

		private void reseta()
		{
			ultimaColunaAlterada = -1;
			for (int i = 0; i < tamanho; i++)
			{
				dados[i] = OBJETO_NULO;
			}
		}

		public void setValor(Object valor, int coluna)
		{
			if (coluna >= 0 && coluna < dados.length)
			{
				dados[coluna] = valor;
				ultimaColunaAlterada = coluna;
			}
		}

		public int getUltimaColunaAlterada()
		{
			return ultimaColunaAlterada;
		}
	}

	protected class Matriz
	{
		private final Vetor dados[];
		private int ultimaLinhaAlterada = -1;
		public final int linhas;
		public final int colunas;

		Matriz(int totalLinhas, int totalColunas)
		{
			this.dados = new Vetor[totalLinhas];
			this.linhas = totalLinhas;
			this.colunas = totalColunas;
		}

		private void reseta()
		{
			ultimaLinhaAlterada = -1;

			for (int i = 0; i < dados.length; i++)
			{
				if (dados[i] != null)
				{
					dados[i].reseta();
				}
			}
		}

		public void setValor(Object valor, int linha, int coluna)
		{
			if (linha >= 0 && linha < linhas)
			{
				if (dados[linha] == null)
				{
					dados[linha] = new Vetor(colunas);
				}
				dados[linha].setValor(valor, coluna);
				ultimaLinhaAlterada = linha;
			}
		}

		public int getUltimaColunaAlterada()
		{
			if (ultimaLinhaAlterada >= 0)
			{
				return dados[ultimaLinhaAlterada].getUltimaColunaAlterada();
			}
			return -1;
		}

		public int getUltimaLinhaAlterada()
		{
			return ultimaLinhaAlterada;
		}
	}

	// mapa usado pelas subclasses (geradas no código Java) para guardar os
	// valores das variáveis que estão sendo inspecionadas
	protected Object variaveisInspecionadas[] = new Object[0];
	protected Vetor vetoresInspecionados[] = new Vetor[0];
	protected Matriz matrizesInspecionadas[] = new Matriz[0];

	protected final StringBuilder stringBuilder = new StringBuilder(512); // string
																			// builder
																			// usado
																			// para
																			// otimizar
																			// as
																			// concatenações

	/*
	 * No momento os tradutores estão sendo instalados na mão.
	 * A idéia é futuramente varrer o package que contém as classes e adicionar os tradutores dinamicamente. 
	 */
	
	static
	{
		tradutoresRuntimeException.put(ArrayIndexOutOfBoundsException.class, new TradutorArrayIndexOutOfBoundsException());
		tradutoresRuntimeException.put(ArithmeticException.class, new TradutorArithmeticException());
	}	
	
	public Programa()
	{
		EntradaSaidaPadrao es = new EntradaSaidaPadrao();

		entrada = es;
		saida = es;
		funcoes = new ArrayList<>();
		observadores = new ArrayList<>();
	}

	protected String concatena(String a, String b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	protected String concatena(String a, int b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	protected String concatena(String a, double b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	protected String concatena(String a, float b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	protected String concatena(String a, char b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	protected String concatena(String a, boolean b)
	{
		stringBuilder.setLength(0);
		return stringBuilder.append(a).append(b).toString();
	}

	public void inspecionaVariavel(int idVariavel)
	{
		if (idVariavel >= 0 && idVariavel < variaveisInspecionadas.length)
		{
			variaveisInspecionadas[idVariavel] = OBJETO_NULO;
		}
		else
		{
			System.out.println(String.format("ID de variável inválido: %d", idVariavel));
		}
	}

	public void inspecionaVetor(int idVetor, int tamanhoVetor)
	{
		if (idVetor >= 0 && idVetor < vetoresInspecionados.length)
		{
			vetoresInspecionados[idVetor] = new Vetor(tamanhoVetor);
		}
		else
		{
			System.out.println(String.format("ID de vetor inválido: %d", idVetor));
		}
	}

	public void inspecionaMatriz(int idMatriz, int linhas, int colunas)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			matrizesInspecionadas[idMatriz] = new Matriz(linhas, colunas);
		}
		else
		{
			System.out.println(String.format("ID de matriz inválido: %d", idMatriz));
		}
	}

	public Object getValorVariavelInspecionada(int idVariavel)
	{
		if (idVariavel >= 0 && idVariavel < variaveisInspecionadas.length)
		{
			return variaveisInspecionadas[idVariavel];
		}
		else
		{
			System.out.println(String.format("ID de variável inválido: %d", idVariavel));
		}
		return OBJETO_NULO;
	}

	public int getUltimaColunaAlteradaNoVetor(int idVetor)
	{
		if (idVetor >= 0 && idVetor < vetoresInspecionados.length)
		{
			Vetor vetor = vetoresInspecionados[idVetor];
			if (vetor != null)
			{
				return vetor.getUltimaColunaAlterada();
			}
		}
		return -1;
	}

	public int getUltimaColunaAlteradaNaMatriz(int idMatriz)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				return matriz.getUltimaColunaAlterada();
			}
		}
		return -1;
	}

	public int getUltimaLinhaAlteradaNaMatriz(int idMatriz)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				return matriz.getUltimaLinhaAlterada();
			}
		}
		return -1;
	}

	public Object getValorNoVetorInspecionado(int idVetor)
	{
		int coluna = getUltimaColunaAlteradaNoVetor(idVetor);
		return getValorNoVetorInspecionado(idVetor, coluna);
	}

	public Object getValorNoVetorInspecionado(int idVetor, int coluna)
	{
		if (idVetor >= 0 && idVetor < vetoresInspecionados.length)
		{
			Vetor vetor = vetoresInspecionados[idVetor];
			if (vetor != null)
			{
				if (coluna >= 0 && coluna < vetor.dados.length)
				{
					Object valor = vetor.dados[coluna];
					if (valor != null)
					{
						return valor;
					}
				}
				else
				{
					System.out.println(String.format("indice inválido acessando o vetor %d (índice: %d)", idVetor, coluna));
				}
			}
			else
			{
				System.out.println(String.format("Vetor no índice %d está nulo!", idVetor));
			}
		}
		else
		{
			System.out.println(String.format("ID de vetor inválido: %d", idVetor));
		}
		return OBJETO_NULO;
	}

	public int getLinhasDaMatriz(int idMatriz)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				return matriz.linhas;
			}
		}
		return 0;
	}

	public int getColunasDaMatriz(int idMatriz)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				return matriz.colunas;
			}
		}
		return 0;
	}

	public int getTamanhoVetor(int idVetor)
	{
		if (idVetor >= 0 && idVetor < vetoresInspecionados.length)
		{
			Vetor vetor = vetoresInspecionados[idVetor];
			if (vetor != null)
			{
				return vetor.tamanho;
			}
		}
		return 0;
	}

	public Object getValorNaMatrizInspecionada(int idMatriz, int linha, int coluna)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				if (linha >= 0 && linha < matriz.dados.length)
				{
					if (coluna >= 0 && coluna < matriz.colunas)
					{
						Vetor vetorLinha = matriz.dados[linha];
						if (vetorLinha != null)
						{
							return vetorLinha.dados[coluna];
						}
					}
					else
					{
						System.out.println(String.format("indice de coluna inválido acessando a matriz %d (índice: %d)", idMatriz, coluna));
					}
				}
				else
				{
					System.out.println(String.format("indice de linha inválido acessando a matriz %d (índice: %d)", idMatriz, linha));
				}
			}
			else
			{
				System.out.println(String.format("Matriz no índice %d está nula!", idMatriz));
			}
		}
		else
		{
			System.out.println(String.format("ID de vetor inválido: %d", idMatriz));
		}
		return OBJETO_NULO;
	}

	// retorna o último valor alterado
	public Object getValorNaMatrizInspecionada(int idMatriz)
	{
		if (idMatriz >= 0 && idMatriz < matrizesInspecionadas.length)
		{
			Matriz matriz = matrizesInspecionadas[idMatriz];
			if (matriz != null)
			{
				int linha = matriz.getUltimaLinhaAlterada();
				int coluna = matriz.getUltimaColunaAlterada();
				return getValorNaMatrizInspecionada(idMatriz, linha, coluna);
			}
		}

		return OBJETO_NULO;
	}

	private void resetaSimbolosInspecionados()
	{
		for (int i = 0; i < variaveisInspecionadas.length; i++)
		{
			if (variaveisInspecionadas[i] != null) // a variável está sendo
													// inspecionada?
			{
				variaveisInspecionadas[i] = OBJETO_NULO;
			}
		}

		for (int i = 0; i < vetoresInspecionados.length; i++)
		{
			if (vetoresInspecionados[i] != null)
			{
				vetoresInspecionados[i].reseta();
			}
		}

		for (int i = 0; i < matrizesInspecionadas.length; i++)
		{
			if (matrizesInspecionadas[i] != null)
			{
				matrizesInspecionadas[i].reseta();
			}
		}
	}

	public void setNumeroLinhas(int numeroLinhas)
	{
		pontosDeParadaAtivados = new boolean[numeroLinhas];
	}

	/**
	 * Permite adicionar um observador à execução do programa. Os observadores
	 * serão notificados sobre o início e o término da execução, bem como erros
	 * em tempo de execução que vierem a ocorrer.
	 *
	 * @param observador
	 *            o observador de execução a ser registrado.
	 * @since 1.0
	 */
	public void adicionarObservadorExecucao(ObservadorExecucao observador)
	{
		if (!observadores.contains(observador))
		{
			observadores.add(observador);
		}
	}

	/**
	 * Remove um observador de execução previamente registrado utilizando o
	 * método
	 * {@link Programa#adicionarObservadorExecucao(br.univali.portugol.nucleo.execucao.ObservadorExecucao) }.
	 * Uma vez removido, o observador não será mais notificado sobre o estado da
	 * execução do programa nem dos erros em tempo de execução que vierem a
	 * ocorrer.
	 *
	 * @param observador
	 *            um observador de execução previamente registrado.
	 * @since 1.0
	 */
	public void removerObservadorExecucao(ObservadorExecucao observador)
	{
		observadores.remove(observador);
	}

	/**
	 * Depura este programa com os parâmetros especificados. Se o programa já
	 * estiver executando/depurando não faz nada.
	 *
	 * @param parametros
	 *            lista de parâmetros que serão passados ao programa no momento
	 *            da execução.
	 * @param estado
	 *
	 * @since 2.0
	 */
	public void executar(String[] parametros, Programa.Estado estado)
	{
		if (!isExecutando())
		{
			this.estado = estado;
			this.interrupcaoSolicitada = false;
			tarefaExecucao = new TarefaExecucao(parametros);
			controleExecucao = POOL_DE_THREADS.submit(tarefaExecucao);

		}
	}

	// usado para reinicializar todas as variáveis globais, assim o programa
	// pode ser re-executado
	protected void inicializar() throws ErroExecucao, InterruptedException
	{
		interrupcaoSolicitada = false;
		resetaSimbolosInspecionados();
	}

	public void continuar(Programa.Estado estado)
	{
		synchronized (LOCK)
		{
			if (isExecutando())
			{
				tarefaExecucao.continuar(estado);
				if (this.isLendo())
				{
					setLeituraIgnorada(true);
				}
				this.estado = estado;
				LOCK.notifyAll();
			}
			else
			{
				throw new IllegalStateException("O programa não pode ser continuado pois não foi iniciado");
			}
		}
	}

	public void ativaPontosDeParada(Set<Integer> linhasComPontosDeParadaAtivados)
	{
		Arrays.fill(pontosDeParadaAtivados, false);
		for (Integer linha : linhasComPontosDeParadaAtivados)
		{
			if (linha < pontosDeParadaAtivados.length)
			{
				pontosDeParadaAtivados[linha] = true;
			}
		}
	}

	protected abstract void executar(String[] parametros) throws ErroExecucao, InterruptedException;

	/**
	 * Implementa uma tarefa para disparar a execução do programa com os
	 * parâmetros e a estratégia selecionada. Futuramente podemos refatorar para
	 * executar a partir de um pool de threads.
	 */
	private final class TarefaExecucao implements Runnable
	{
		private final String[] parametros;
		private final ResultadoExecucao resultadoExecucao;

		public TarefaExecucao(String[] parametros)
		{
			this.parametros = parametros;
			this.resultadoExecucao = new ResultadoExecucao();
		}

		@Override
		public void run()
		{
			long horaInicialExecucao = System.currentTimeMillis();

			try
			{
				notificarInicioExecucao();
				inicializaBibliotecasIncluidas();
				inicializar(); // reinicializa todas as variaveis antes de
								// executar
				executar(parametros);
			}
			catch (OutOfMemoryError erroMemoria)
			{
				ErroMemoriaInsuficiente erroExecucao = new ErroMemoriaInsuficiente();
				erroExecucao.setLinha(ultimaLinha);
				erroExecucao.setColuna(ultimaColuna);

				resultadoExecucao.setModoEncerramento(ModoEncerramento.ERRO);
				resultadoExecucao.setErro(erroExecucao);
			}
			catch (StackOverflowError e) 
			{
				ErroExecucao erroExecucao = new ErroEstouroPilha();

				erroExecucao.setLinha(ultimaLinha);
				erroExecucao.setColuna(ultimaColuna);

				resultadoExecucao.setModoEncerramento(ModoEncerramento.ERRO);
				resultadoExecucao.setErro(erroExecucao);
			}
			catch (RuntimeException e)
			{
				ErroExecucao erroExecucao = new ErroExecucaoNaoTratado(e);

				TradutorRuntimeException<? extends RuntimeException> tradutor = tradutoresRuntimeException.get(e.getClass());

				if (tradutor != null)
				{
					erroExecucao = tradutor.traduzir(e, Programa.this, ultimaLinha, ultimaColuna);
				}
				
				if (erroExecucao.getLinha() == 0)
				{
					erroExecucao.setLinha(ultimaLinha);
				}
				
				if (erroExecucao.getColuna() == 0)
				{
					erroExecucao.setColuna(ultimaColuna);
				}

				resultadoExecucao.setModoEncerramento(ModoEncerramento.ERRO);
				resultadoExecucao.setErro(erroExecucao);
			}
			catch (ErroExecucao erroExecucao)
			{
				/*
				 * Este tratamento de erros é legado da versão antiga do núcleo
				 * que utilizava o Interpretador de código.
				 * 
				 * Nesta nova versão com código compilado, este erro nunca será
				 * jogado. No lugar do ErroExecucao, será sempre jogado um
				 * RuntimeException, que deverá então ser tratado e traduzido de
				 * acordo com o tipo de erro.
				 * 
				 * Em algum momento, devemos mudar a assinatura do método
				 * "executar" para que não jogue mais esta exceção. Não farei
				 * agora porque teria que alterar o gerador de código Java e os
				 * testes unitários.
				 * 
				 */

				erroExecucao.setLinha(ultimaLinha);
				erroExecucao.setColuna(ultimaColuna);

				resultadoExecucao.setModoEncerramento(ModoEncerramento.ERRO);
				resultadoExecucao.setErro(erroExecucao);
			}
			
			catch (InterruptedException excecao)
			{
				resultadoExecucao.setModoEncerramento(ModoEncerramento.INTERRUPCAO);
			}
			catch (Exception excecao)
			{
				if (excecao.getCause() instanceof InterruptedException)
				{
					resultadoExecucao.setModoEncerramento(ModoEncerramento.INTERRUPCAO);
				}
				else
				{
					ErroExecucao erroExecucao = new ErroExecucaoNaoTratado(excecao);

					erroExecucao.setLinha(ultimaLinha);
					erroExecucao.setColuna(ultimaColuna);

					resultadoExecucao.setModoEncerramento(ModoEncerramento.ERRO);
					resultadoExecucao.setErro(erroExecucao);
				}
			}
			finally
			{
				try
				{
					finalizaBibliotecasIncluidas();
				}
				catch (InterruptedException | ErroExecucaoBiblioteca ex)
				{
					Logger.getLogger(Programa.class.getName()).log(Level.SEVERE, "Não era pra acontecer", ex);
				}
			}

			resultadoExecucao.setTempoExecucao(System.currentTimeMillis() - horaInicialExecucao);

			notificarEncerramentoExecucao(resultadoExecucao);

			observadores.clear(); // remove todos os listeners quando termina de
									// executar
		}

		public void continuar(Programa.Estado estado)
		{
			synchronized (LOCK)
			{
				if (isLendo())
				{
					setLeituraIgnorada(true);
				}

				Programa.this.estado = estado;
				LOCK.notifyAll();
			}
		}
	}

	/**
	 * Interrompe a execução deste programa. Não tem nenhum efeito se o programa
	 * não estiver executando.
	 *
	 * @since 1.0
	 */
	public void interromper()
	{
		if (isExecutando())
		{
			controleExecucao.cancel(true);
			interrupcaoSolicitada = true;
		}
	}

	private boolean podeParar(int linha)
	{
		// pode parar quando está no modo STEP_OVER ou quando está no modo
		// BREAK_POINT e tem um ponto de parada ativo na linha em execução

		if (estado == Estado.STEP_OVER)
		{
			return true;
		}

		if (linha >= 0 && linha < pontosDeParadaAtivados.length)
		{
			return estado == Estado.BREAK_POINT && pontosDeParadaAtivados[linha];
		}

		return false;
	}

	protected void realizarParada(int linha, int coluna) throws ErroExecucao, InterruptedException
	{
		ultimaLinha = linha;
		ultimaColuna = coluna;

		if (podeParar(linha))
		{
			disparaDestacar(linha);
			synchronized (LOCK)
			{
				LOCK.wait();
			}
			// else if ( this.estado == Estado.STEP_INTO)
			// {
			// disparaDestacar(trechoCodigoFonte);
			// }
			// else
			// {
			// disparaDestacar((trechoCodigoFonte != null) ?
			// trechoCodigoFonte.getLinha() : -1);
			// }
		}
	}

	private void disparaDestacar(int linha)
	{
		if (linha >= 0)
		{
			for (ObservadorExecucao observador : observadores)
			{
				observador.highlightLinha(linha);
			}
		}
	}

	/**
	 * Obtém a lista de funções declaradas atualmente no programa
	 *
	 * @return a lista de funções
	 */
	public List<String> getFuncoes()
	{
		return funcoes;
	}

	/**
	 * Define a lista de funções declaradas atualmente no programa
	 *
	 * @param funcoes
	 *            a lista de funções
	 */
	public void setFuncoes(List<String> funcoes)
	{
		this.funcoes = funcoes;
	}

	public File getArquivoOrigem()
	{
		return arquivoOrigem;
	}

	public void setArquivoOrigem(File arquivoOrigem)
	{
		this.arquivoOrigem = arquivoOrigem;
	}

	/**
	 * Obtém a ASA que representa este programa.
	 *
	 * @return a ASA que representa este programa
	 * @since 1.0
	 */
	public ASAPrograma getArvoreSintaticaAbstrata()
	{
		return arvoreSintaticaAbstrataPrograma;
	}

	/**
	 * Define a ASA que representa este programa.
	 *
	 * @param arvoreSintaticaAbstrataPrograma
	 *            a ASA que representa este programa.
	 * @since 1.0
	 */
	public void setArvoreSintaticaAbstrata(ASAPrograma arvoreSintaticaAbstrataPrograma)
	{
		this.arvoreSintaticaAbstrataPrograma = arvoreSintaticaAbstrataPrograma;
	}

	/**
	 * Define o nome da função que deverá ser chamada para dar início à execução
	 * do programa. Caso não tenho sido declarada uma função com este nome no
	 * código fonte, será gerado um erro em tempo de execução.
	 *
	 * @param funcaoInicial
	 *            o nome da função que será o ponto de partida do programa.
	 * @since 1.0
	 */
	public void setFuncaoInicial(String funcaoInicial)
	{
		this.funcaoInicial = funcaoInicial;
	}

	/**
	 * Obtém o nome da função que está atualmente definida como ponto de partida
	 * para este programa.
	 *
	 * @return o nome da função que está atualmente definida como ponto de
	 *         partida.
	 * @since 1.0
	 */
	public String getFuncaoInicial()
	{
		return funcaoInicial;
	}

	/**
	 * Obtém a interface para entrada de dados que está atualmente registrada
	 * para este programa.
	 *
	 * @return a interface para entrada de dados que está atualmente registrada.
	 * @since 1.0
	 */
	public Entrada getEntrada()
	{
		return entrada;
	}

	/**
	 * Obtém a interface para saída de dados que está atualmente registrada para
	 * este programa.
	 *
	 * @return a interface para saída de dados que está atualmente registrada.
	 * @since 1.0
	 */
	public Saida getSaida()
	{
		return saida;
	}

	/**
	 * Define a interface para entrada de dados deste programa.
	 *
	 * @param entrada
	 *            a interface para entrada de dados a ser registrada para este
	 *            programa.
	 * @since 1.0
	 */
	public void setEntrada(Entrada entrada)
	{
		this.entrada = entrada;
	}

	/**
	 * Define a interface para saída de dados deste programa.
	 *
	 * @param saida
	 *            a interface para saída de dados a ser registrada para este
	 *            programa.
	 * @since 1.0
	 */
	public void setSaida(Saida saida)
	{
		this.saida = saida;
	}

	public void setResultadoAnalise(ResultadoAnalise resultadoAnalise)
	{
		this.resultadoAnalise = resultadoAnalise;
		this.resultadoAnalise.setPrograma(this);
	}

	public ResultadoAnalise getResultadoAnalise()
	{
		return resultadoAnalise;
	}

	/**
	 * Verifica se este programa está em execução.
	 *
	 * @return <code>true</code> se o programa estiver executando, caso
	 *         contrário retorna <code>false</code>.
	 * @since 1.0
	 */
	public boolean isExecutando()
	{
		return (tarefaExecucao != null && !interrupcaoSolicitada);
	}

	/**
	 * Notifica todos os observadores registrados sobre o início da execução
	 * deste programa.
	 */
	private void notificarInicioExecucao()
	{
		for (ObservadorExecucao observador : observadores)
		{
			observador.execucaoIniciada(this);
		}
	}

	private void notificarExecucaoPausada()
	{
		for (ObservadorExecucao observador : observadores)
		{
			observador.execucaoPausada();
		}
	}

	private void notificarExecucaoResumida()
	{
		for (ObservadorExecucao observador : observadores)
		{
			observador.execucaoResumida();
		}
	}

	/**
	 * Notifica todos os observadores registrados sobre o término da execução
	 * deste programa.
	 *
	 * @param resultadoExecucao
	 *            objeto contendo informações sobre o motivo do encerramento do
	 *            programa e eventos ocorridos durante a execução.
	 * @since 1.0
	 */
	private void notificarEncerramentoExecucao(ResultadoExecucao resultadoExecucao)
	{
		tarefaExecucao = null;
		controleExecucao = null;

		for (ObservadorExecucao observador : observadores)
		{
			observador.execucaoEncerrada(this, resultadoExecucao);
		}
	}

	public void setDiretorioTrabalho(File diretorioTrabalho)
	{
		if (diretorioTrabalho.isDirectory() && diretorioTrabalho.exists())
		{
			this.diretorioTrabalho = diretorioTrabalho;
		}
		else
		{
			throw new IllegalArgumentException(String.format("Diretório de trabalho inválido. O caminho '%s' não existe ou não representa um diretório", diretorioTrabalho.getAbsolutePath()));
		}
	}

	public File resolverCaminho(File caminho)
	{
		if (!caminho.isAbsolute())
		{
			return new File(diretorioTrabalho, caminho.getPath());
		}

		return caminho;
	}

	@Override
	public boolean equals(Object obj)
	{
		if (obj instanceof Programa)
		{
			return ((Programa) obj) == this;
		}

		return false;
	}

	@Override
	public int hashCode()
	{
		return System.identityHashCode(this);
	}

	protected void limpa() throws ErroExecucao
	{
		try
		{
			saida.limpar();
		}
		catch (Exception e)
		{
			throw new ErroExecucao()
			{
				@Override
				protected String construirMensagem()
				{
					return "Erro de execução limpando a saída!";
				}
			};
		}
	}

	protected void escreva(Object... listaParametrosPassados) throws InterruptedException
	{
		if (saida == null)
		{
			throw new IllegalStateException("A saída do Programa está nula!");
		}

		for (Object valor : listaParametrosPassados)
		{

			if (valor instanceof String)
			{
				if (valor.equals("${show developers}"))
				{
					valor = "\n\nDesenvolvedores:\n\nFillipi Domingos Pelz\nLuiz Fernando Noschang\n\n";
				}

				saida.escrever((String) valor);
			}
			else if (valor instanceof Boolean)
			{
				saida.escrever((Boolean) valor);
			}
			else if (valor instanceof Character)
			{
				saida.escrever((Character) valor);
			}
			else if (valor instanceof Double)
			{
				saida.escrever((Double) valor);
			}
			else if (valor instanceof Integer)
			{
				saida.escrever((Integer) valor);
			}
		}
	}

	private void setLendo(boolean lendo)
	{
		synchronized (LOCK)
		{
			this.lendo = lendo;
		}
	}

	private boolean isLendo()
	{
		synchronized (LOCK)
		{
			return lendo;
		}
	}

	private void setLeituraIgnorada(boolean leituraIgnorada)
	{
		synchronized (LOCK)
		{
			this.leituraIgnorada = leituraIgnorada;
		}
	}

	private boolean isLeituraIgnorada()
	{
		synchronized (LOCK)
		{
			return leituraIgnorada;
		}
	}

	protected double leiaReal() throws ErroExecucao, InterruptedException
	{
		return (Double) leia(TipoDado.REAL);
	}

	protected int leiaInteiro() throws ErroExecucao, InterruptedException
	{
		return (Integer) leia(TipoDado.INTEIRO);
	}

	protected boolean leiaLogico() throws ErroExecucao, InterruptedException
	{
		return (Boolean) leia(TipoDado.LOGICO);
	}

	protected char leiaCaracter() throws ErroExecucao, InterruptedException
	{
		return (Character) leia(TipoDado.CARACTER);
	}

	protected String leiaCadeia() throws ErroExecucao, InterruptedException
	{
		return (String) leia(TipoDado.CADEIA);
	}

	private Object leia(TipoDado tipoDado) throws ErroExecucao, InterruptedException
	{
		assert (entrada != null);

		setLendo(true);

		try
		{
			InputHandler mediador = new InputHandler();
			entrada.solicitaEntrada(tipoDado, mediador);

			// Se for verdadeiro, significa que a entrada é assíncrona,
			// então devemos esperar a leitura da entrada. Caso contrário,
			// a entrada é síncrona, podemos seguir em frente e pegar o valor
			if (mediador.getValor() == null && !mediador.isCancelado())
			{
				synchronized (LOCK)
				{
					notificarExecucaoPausada();
					LOCK.wait();
				}
			}

			if (!mediador.isCancelado())
			{
				if (!isLeituraIgnorada())
				{
					return mediador.getValor();
				}
				else
				{
					throw new ErroValorEntradaInvalido(tipoDado, 0, 0);
				}
			}
			else
			{
				throw new ErroValorEntradaInvalido(tipoDado, 0, 0);
			}
		}
		finally
		{
			setLendo(false);
			notificarExecucaoResumida();
		}

	}

	private class InputHandler implements InputMediator, Armazenador
	{
		private Object valor;
		private boolean cancelado = false;

		@Override
		public Object getValor()
		{
			synchronized (LOCK)
			{
				return valor;
			}
		}

		@Override
		public void setValor(Object valor)
		{
			synchronized (LOCK)
			{
				this.valor = valor;
				LOCK.notifyAll();
			}
		}

		@Override
		public void cancelarLeitura()
		{
			synchronized (LOCK)
			{
				this.cancelado = true;
				LOCK.notifyAll();
			}
		}

		public boolean isCancelado()
		{
			synchronized (LOCK)
			{
				return cancelado;
			}
		}
	}

	private List<Biblioteca> obterBibliotecasIncluidas() throws ErroExecucaoBiblioteca, InterruptedException
	{
		try
		{
			Field atributos[] = this.getClass().getDeclaredFields();
			List<Biblioteca> bibliotecas = new ArrayList<>();

			for (Field atributo : atributos)
			{
				boolean acessoPermitido = atributo.isAccessible();
				if (!acessoPermitido)
				{
					atributo.setAccessible(true);
				}
				if (atributo.get(this) instanceof Biblioteca)
				{
					bibliotecas.add((Biblioteca) atributo.get(this));
				}
				atributo.setAccessible(acessoPermitido);
			}

			return bibliotecas;
		}
		catch (IllegalAccessException | IllegalArgumentException | SecurityException ex)
		{
			throw new ErroExecucaoBiblioteca(ex);
		}
	}

	protected void inicializaBibliotecasIncluidas() throws ErroExecucaoBiblioteca, InterruptedException
	{
		List<Biblioteca> bibliotecasReservadas = obterBibliotecasIncluidas();

		for (Biblioteca biblioteca : bibliotecasReservadas)
		{
			biblioteca.inicializar(this, bibliotecasReservadas);
		}
	}

	protected void finalizaBibliotecasIncluidas() throws ErroExecucaoBiblioteca, InterruptedException
	{
		List<Biblioteca> bibliotecasReservadas = obterBibliotecasIncluidas();

		for (Biblioteca biblioteca : bibliotecasReservadas)
		{
			biblioteca.finalizar();
		}
	}
}
