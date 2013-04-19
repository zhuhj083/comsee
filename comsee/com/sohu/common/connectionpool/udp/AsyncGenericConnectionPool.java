/*
 * Created on 2006-11-24
 *
 */
package com.sohu.common.connectionpool.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;

import com.sohu.common.connectionpool.RequestFactory;

/**
 * ���ӳ�
 * 
 * 1. �ṩ������������. ����������,���ⲻ�����κεĴ�����Ϣ.
 * 2. ����������. ������������ַ.
 * 3. ֧�ֶ��߳�
 * 
 * @author LiuMingzhu (mingzhuliu@sohu-inc.com)
 *
 */
public abstract class AsyncGenericConnectionPool extends ServerConfig{

	/// random
	protected static final Random random = new Random();
	
	/// ���������״̬��Ϣ
	protected ServerStatus[] status ;
	///	socket����ʧ��ʱ�����Զ�ѡ��һ��������ӣ����Ʒ��inplaceConnectionLife��query���Զ��Ͽ�
	protected int inplaceConnectionLife = 500;

	Selector selector;
	
	protected Receiver recver;
	protected Sender sender;
	
	protected Object recverLock = new Object();
	protected Object senderLock = new Object();
	
	// ���Ӷ����factory
	protected AsyncClientFactory factory;
	// �������factory
	protected RequestFactory requestFactory;

	/**
	 * �������ӳ�ʵ��
	 * @param factory ���Ӷ����factory���ض������ӳ�Ҫ�Լ�ʵ�����Ӷ���
	 * @param name ���ӳص����֣��������ⶨ�塣��Ϊnull�ᰴ��"Pool"��������
	 */
	protected AsyncGenericConnectionPool(AsyncClientFactory factory, String name)
	{
		this(factory, name, null);
	}
	/**
	 * ������ʵ��
	 * @param factory ���Ӷ����factory���ض������ӳ�Ҫ�Լ�ʵ�����Ӷ���
	 * @param name ���ӳص����֣��������ⶨ�塣��Ϊnull�ᰴ��"Pool"��������
	 * @param reqestFactory ���Ͷ���(Request)��factory
	 */
	protected AsyncGenericConnectionPool(AsyncClientFactory factory, String name, RequestFactory reqestFactory)
	{
		this.factory = factory;
		if( name != null){
			this.name = name;
		}
		this.requestFactory = reqestFactory;
	}

	public void init() throws Exception{
		
		ArrayList servers = new ArrayList();
		
		if ( this.servers == null ) throw new IllegalArgumentException("config is NULL");
		
		String[] list = pat.split( this.servers );
	
		for (int i = 0 ; i < list.length ; i++ ) {
			ServerStatus ss = new ServerStatus( list[i], this );
			servers.add( servers.size() , ss);
		}

		ServerStatus[] serverStatus = (ServerStatus[])servers.toArray( new ServerStatus[servers.size()] );

		selector = Selector.open();
		
		this.status = serverStatus;
		
		recver = new Receiver(this);
		sender = new Sender(this);
		
		recver.startThread();
		sender.startThread();
	}

	/**
	 * ��ü�¼��ʵ��
	 * @return
	 */
	protected abstract Log getLogger();

	public int sendRequest( AsyncRequest request ){
		
		if( request == null ){			
			return -1;
		}
		
		if( ! request.isValid() ){
			request.illegalRequest();
			request.setConnectionErrorStatus(-2);
			return -2;
		}
		
		int serverCount = this.getServerIdCount();
		int ret = request.getServerId( serverCount );
		
		// ��������״̬
		if( ! isServerAvaliable( ret )){
//			System.out.println("server is not avaliable");
			int avaliableServerCount = 0;
			for(int i=0; i< getServerIdCount() ; i++){
				if( isServerAvaliable( i ) ){
					avaliableServerCount ++;
				}
			}
			if( avaliableServerCount <= 0 ){
				request.serverDown();
				request.setConnectionErrorStatus(-1);
				return -1;
			}
			// ���Դ���.
			int inc = ( request.getServerId( avaliableServerCount) ) + 1;

			int finalIndex = ret ;

			int i=0;
			do{
				int j=0;
				boolean find = false;
				do {
					finalIndex = ( finalIndex +1 ) % serverCount;
					if( isServerAvaliable( finalIndex ) ){
						find = true;
						break;
					}
					j++;
				}while( j < serverCount );
				
				if( !find ){
					request.serverDown();
					request.setConnectionErrorStatus(-1);
					return -1;
				}

				i++;
			}while( i<inc);

			ret = finalIndex;
		}
		
		return sendRequestById(ret, request);
	}

	/**
	 * ��ָ����Server��������
	 * ���������ӳصĺܶ���Բ���
	 * ���ã������ڶ����ӳ��ڲ�������Ϥ�Ŀ�����
	 * @param serverId
	 * @param request
	 * @return
	 */
	public int sendRequestById( int serverId, AsyncRequest request ){
		// ���serverId�ĺϷ���
		assert( serverId >= 0 && serverId < this.getServerIdCount() );
		if( serverId<0 || serverId>=this.getServerIdCount() ){
			request.illegalRequest();
			request.setConnectionErrorStatus(-1);
			return -1;
		}
		
		request.setServerId(serverId);
		ServerStatus ss = getStatus(serverId);
		
		if( ss == null ){
			request.serverDown();
			request.setConnectionErrorStatus(-2);
			return -2;
		}
		
		request.setServer(ss);
		request.setServerInfo( ss.getServerInfo() );
		request.queueSend();
		sender.senderSendRequest(request);

		return 0;
		
	}

	/**
	 * @return
	 */
	public int getServerIdBits() {
		return 0;
	}

	/**
	 * @return
	 */
	public int getServerIdMask() {
		return 0;
	}

	/**
	 * @param i
	 * @return	��į������
	 */
	public InetSocketAddress getServer(int i) {
		return status[i].getAddr();
	}

	/**
	 * @return Returns the inplaceConnectionLife.
	 */
	public int getInplaceConnectionLife() {
		return inplaceConnectionLife;
	}
	
	/**
	 * @param inplaceConnectionLife The inplaceConnectionLife to set.
	 */
	public void setInplaceConnectionLife(int inplaceConnectionLife) {
		this.inplaceConnectionLife = inplaceConnectionLife;
	}
	
	private static Pattern pat = Pattern.compile("\\s+");
	

	public ServerStatus[] getAllStatus() {
		return status;
	}
	/**
	 * ����ָ����ŵķ�������״̬����.
	 * @param i
	 * @return ���ָ����ŵķ�����������,�򷵻�null
	 */
	public ServerStatus getStatus(int i ){
		if( status != null 
				&& i>=0 
				&& i<status.length ){
			return status[i];
		} else {
			return null;
		}
	}

	public boolean isServerAvaliable(int i){
		long now = System.currentTimeMillis();
		
		ServerStatus ss = null;
		if( status !=null && i>=0 && i< status.length){
			ss = status[i];
		}
		if( ss == null ){
			return false;
		}
		boolean ret = ( ss.recentErrorNumber <= this.getMaxErrorsBeforeSleep()
				|| (now - ss.downtime ) >= this.getSleepMillisecondsAfterTimeOutError() );
		if( !ret ){
			Log logger = getLogger();
			if( logger != null && logger.isTraceEnabled() )
				logger.trace("server is not avaliable:" + ss.getServerInfo() );
		}
		return ret;
	}
	
	/**
	 * �������i��Ӧ�ķ����������ӳ��ж�Ӧ�ļ�ֵ.
	 * �����Ӧ�ķ������Ƿ�(������),�򷵻�null;
	 * @param i
	 * @return
	 */
	public Object getServerKey( int i ){
		if( status !=null 
				&& i >= 0
				&& i < status.length
				&& status[i] !=null
				&& status[i].key != null
			) {
			return status[i].key;
		} else {
			return null;
		}
	}

	/**
	 * ���й�����, server���ܻ�down��, ��Ҫ��̬��������������ֵ.
	 * @return
	 */
	public int getServerIdCount(){
		if( status == null ){
			return 0;
		}else {
			return status.length;
		}
	}
	public InetSocketAddress getSocketAddress( int i ){
		if( status == null 
				|| i<0
				|| i>=status.length
				|| status[i] == null
		  ){
			return null;
		} else {
			return status[i].getAddr();
		}
	}
	public void finalize(){
		destroy();
	}
	
	/**
	 * �������ӳض���
	 */
	public void destroy(){
		sender.stopThread();
		sender = null;
		recver.stopThread();
		recver = null;
		ServerStatus[] temp = status;
		status = null;
		if( temp != null ){
			for(int i=0;i<temp.length; i++){
				ServerStatus ss = temp[i];
				if( ss == null ) continue;
				ss.destroy();
			}
		}
		try{
			this.selector.close();
		}catch( IOException e){
			// dummy
		}
	}
	public String status(){
		StringBuffer sb = new StringBuffer();
		sb.append( "\nPool Status: ");
		sb.append( this.getName() );
		sb.append( '\n' );
		
		for( int i=0; i< this.status.length; i++){
			status[i].status( sb );
		}
		
		if( getLogger().isInfoEnabled() ){
			getLogger().info( sb.toString() );
		}
		return sb.toString();
	}

	/**
	 * @return the requestFactory
	 */
	public RequestFactory getRequestFactory() {
		return requestFactory;
	}

	/**
	 * @param requestFactory the requestFactory to set
	 */
	public void setRequestFactory(RequestFactory requestFactory) {
		this.requestFactory = requestFactory;
	}

}