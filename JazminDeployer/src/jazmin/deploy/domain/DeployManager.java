/**
 * 
 */
package jazmin.deploy.domain;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jazmin.core.Jazmin;
import jazmin.log.Logger;
import jazmin.log.LoggerFactory;
import jazmin.misc.SshUtil;
import jazmin.util.BeanUtil;
import jazmin.util.FileUtil;
import jazmin.util.JSONUtil;
import jazmin.util.JSONUtil.JSONPropertyFilter;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

/**
 * @author yama
 * 6 Jan, 2015
 */
public class DeployManager {
	private static Logger logger=LoggerFactory.get(DeployManager.class);
	//
	private static Map<String,Instance>instanceMap;
	private static Map<String,User>userMap;
	private static Map<String,Machine>machineMap;
	private static Map<String,AppPackage>packageMap;
	private static Map<String,Application>applicationMap;
	private static List<PackageDownloadInfo>downloadInfos;
	private static WatchKey key;
	private static GraphVizRenderer graphVizRenderer;
	private static StringBuffer actionReport=new StringBuffer();
	//
	static{
		instanceMap=new ConcurrentHashMap<String, Instance>();
		machineMap=new ConcurrentHashMap<String, Machine>();
		packageMap=new ConcurrentHashMap<String, AppPackage>();
		applicationMap=new ConcurrentHashMap<String, Application>();
		userMap=new ConcurrentHashMap<String, User>();
		downloadInfos=Collections.synchronizedList(new LinkedList<PackageDownloadInfo>());
		graphVizRenderer=new GraphVizRenderer();
	}
	//
	private static String workSpaceDir="";
	//
	@SuppressWarnings("rawtypes")
	public static void setup() throws Exception {
		workSpaceDir=Jazmin.environment.getString("deploy.workspace","./workspace/");
		WatchService watcher = FileSystems.getDefault().newWatchService();
		String configDir = workSpaceDir;
		Path dir = FileSystems.getDefault().getPath(configDir+"package");
		key = dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY,
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_DELETE);
		Runnable r = () -> {
			while (true) {
				try {
					key = watcher.take();
					for (WatchEvent event : key.pollEvents()) {
						Kind kind = event.kind();
						logger.info("Event on " + event.context().toString()
								+ " is " + kind);
						reloadPackage();
					}
					key.reset();
				} catch (InterruptedException e) {
					logger.catching(e);
				}
			}
		};
		new Thread(r).start();
		//
		Velocity.init();
	}
	//
	public static void reload(){
		String configDir=workSpaceDir;
		configDir+="config";
		try{
			reloadApplicationConfig(configDir);
			checkApplicationConfig();
			reloadMachineConfig(configDir);
			reloadInstanceConfig(configDir);
			reloadUserConfig(configDir);
			
			setInstancePrioriy();
			reloadPackage();
		}catch(Exception e){
			logger.error(e.getMessage(),e);
		}
	}
	
	public static String getConfigFile(String file){
		String configDir=workSpaceDir+"config";
		try {
			return FileUtil.getContent(new File(configDir,file));
		} catch (IOException e) {
			logger.catching(e);
			return null;
		}
	}
	
	//
	public static void saveConfigFile(String file,String value){
		String configDir=workSpaceDir+"config";
		try {
			FileUtil.saveContent(value, new File(configDir,file));
			reload();
		} catch (IOException e) {
			logger.catching(e);
		}
	}
	
	//
	public static void setPackageVersion(List<Instance>instances,String version){
		if(version==null||version.trim().isEmpty()){
			return;
		}
		if(!Pattern.matches("\\d\\d*\\.\\d\\d*\\.\\d\\d*",version)){
			throw new IllegalArgumentException("version format just like :1.0.0");
		}
		instances.forEach(i->i.packageVersion=(version));
	}
	//
	public static void saveInstanceConfig()throws Exception{
		String configDir=workSpaceDir;
		configDir+="config";
		File configFile=new File(configDir,"instance.json");
		List<Instance>list=getInstances();
		Collections.sort(list,(o1,o2)->o1.priority-o2.priority);
		if(configFile.exists()){
			String result=JSONUtil.toJson(
					list,
					new JSONPropertyFilter(){
						@Override
						public boolean apply(Object arg0, String name,
								Object arg2) {
							if(name.equals("alive")||
									name.equals("machine")||
									name.equals("priority")){
								return false;
							}
							return true;
						}
					},true);
			FileUtil.saveContent(result, configFile);
		}
	}
	//
	private static void reloadPackage(){
		packageMap.clear();
		String packageDir=workSpaceDir;
		packageDir+="package";
		
		File packageFolder=new File(packageDir);
		if(packageFolder.exists()&&packageFolder.isDirectory()){
			for(File ff:packageFolder.listFiles()){
				if(ff.isFile()&&!ff.isHidden()){
					AppPackage pkg=new AppPackage();
					String fileName=ff.getName();
					pkg.id=(fileName);
					pkg.file=(ff.getAbsolutePath());
					pkg.lastModifiedTime=new Date(ff.lastModified());
					packageMap.put(pkg.id,pkg);
				}
			}	
		}
	}
	//
	public static User validate(String u,String p){
		User ui=userMap.get(u);
		if(ui==null){
			return null;
		}
		if(ui.password.equals(p)){
			return ui;
		}
		return null;
	}
	//
	public static List<PackageDownloadInfo>getPackageDownloadInfos(){
		return downloadInfos;
	}
	//
	public static void addPackageDownloadInfo(PackageDownloadInfo info){
		downloadInfos.add(info);
	}
	//
	public static void removePackageDownloadInfo(PackageDownloadInfo info){
		downloadInfos.remove(info);
	}
	//
	public static List<AppPackage>getPackages(){
		return new ArrayList<AppPackage>(packageMap.values());
	}
	//
	public static List<Application>getApplications(String search){
		if(search==null||search.trim().isEmpty()){
			return new ArrayList<Application>();
		}
		String queryBegin="select * from "+Application.class.getName()+" where 1=1 and ";
		return BeanUtil.query(getApplications(),queryBegin+search);
	}
	//
	public static List<Application>getApplications(){
		return new ArrayList<Application>(applicationMap.values());
	}
	//
	public static List<Application>getApplicationBySystem(String system){
		List<Application>result=new ArrayList<Application>();
		for(Application a:applicationMap.values()){
			if(a.system.equals(system)){
				result.add(a);
			}
		}
		return result;
	}
	//
	public static List<AppPackage>getPackages(String search)throws Exception{
		if(search==null||search.trim().isEmpty()){
			return new ArrayList<AppPackage>();
		}
		String queryBegin="select * from "+AppPackage.class.getName()+" where 1=1 and ";
		return BeanUtil.query(getPackages(),queryBegin+search);
	}
	//
	//
	public static List<RepoItem>getRepoItems(String search)throws Exception{
		if(search==null||search.trim().isEmpty()){
			return new ArrayList<RepoItem>();
		}
		String queryBegin="select * from "+RepoItem.class.getName()+" where 1=1 and ";
		return BeanUtil.query(getRepoItems(),queryBegin+search);
	}
	//	
	public static List<RepoItem>getRepoItems(){
		List<RepoItem>items= new ArrayList<RepoItem>();
		String repoDir=workSpaceDir+"repo";
		File ff=new File(repoDir);
		if(ff.isDirectory()){
			for(File f:ff.listFiles()){
				if(f.isFile()){
					RepoItem i=new RepoItem();
					i.file=f.getAbsolutePath();
					i.id=f.getName();
					i.lastModifiedTime=new Date(f.lastModified());
					items.add(i);
				}
			}
		}
		return items;
	}
	//
	public static List<Instance>getInstances(String search)throws Exception{
		if(search==null||search.trim().isEmpty()){
			return new ArrayList<Instance>();
		}
		String queryBegin="select * from "+Instance.class.getName()+" where 1=1 and ";
		return BeanUtil.query(getInstances(),queryBegin+search);
	}
	//
	public static Instance getInstance(String id){
		return instanceMap.get(id);
	}
	//
	public static List<Machine>getMachines(String search){
		if(search==null||search.trim().isEmpty()){
			return new ArrayList<Machine>();
		}
		String queryBegin="select * from "+Machine.class.getName()+" where 1=1 and ";
		return BeanUtil.query(getMachines(),queryBegin+search);
	}
	//
	public static List<Machine>getMachines(){
		return new ArrayList<Machine>(machineMap.values());
	}
	public static Machine getMachine(String id){
		return machineMap.get(id);
	}
	//
	public static List<Instance>getInstances(){
		return new ArrayList<Instance>(instanceMap.values());
	}
	//
	private static void reloadMachineConfig(String configDir)throws Exception{
		File configFile=new File(configDir,"machine.json");
		if(configFile.exists()){
			machineMap.clear();
			logger.info("load config from:"+configFile.getAbsolutePath());
			String ss=FileUtil.getContent(configFile);
			List<Machine>machines= JSONUtil.fromJsonList(ss,Machine.class);
			machines.forEach(in->machineMap.put(in.id,in));
		}else{
			logger.warn("can not find :"+configFile);
		}
	}
	private static void reloadApplicationConfig(String configDir)throws Exception{
		File configFile=new File(configDir,"application.json");
		if(configFile.exists()){
			applicationMap.clear();
			logger.info("load application from:"+configFile.getAbsolutePath());
			String ss=FileUtil.getContent(configFile);
			List<Application>apps= JSONUtil.fromJsonList(ss,Application.class);
			apps.forEach(in->{
				applicationMap.put(in.id,in);
			});
		}else{
			logger.warn("can not find :"+configFile);
		}
	}
	private static void reloadUserConfig(String configDir)throws Exception{
		File configFile=new File(configDir,"user.json");
		if(configFile.exists()){
			userMap.clear();
			logger.info("load user from:"+configFile.getAbsolutePath());
			String ss=FileUtil.getContent(configFile);
			List<User>apps= JSONUtil.fromJsonList(ss,User.class);
			apps.forEach(in->{
				userMap.put(in.id,in);
			});
		}else{
			logger.warn("can not find :"+configFile);
		}
	}
	//
	private static void checkApplicationConfig(){
		for(Application a:applicationMap.values()){
			for(String depend:a.depends){
				if(!applicationMap.containsKey(depend)){
					logger.error("can not find depend application {} for {}",depend,a.id);
				}
				//
				if(depend.equals(a)){
					logger.error("can not depend self {}",depend);	
				}
			}
		}
		//do topsearch and cal priority
		int idx=0;
		for(Application a:TopSearch.topSearch(getApplications())){
			a.priority=idx++;
		}
	}
	//
	private static void reloadInstanceConfig(String configDir)throws Exception{
		File configFile=new File(configDir,"instance.json");
		if(configFile.exists()){
			instanceMap.clear();
			logger.info("load config from:"+configFile.getAbsolutePath());
			String ss=FileUtil.getContent(configFile);
			List<Instance>instances= JSONUtil.fromJsonList(ss,Instance.class);
			AtomicInteger ai=new AtomicInteger();
			instances.forEach(in->{
				if(in.user==null){
					in.user=("");
				}
				if(in.password==null){
					in.password=("");
				}
				if(in.packageVersion==null){
					in.packageVersion=("1.0.0");
				}
				in.priority=(ai.incrementAndGet());
				Machine m=machineMap.get(in.machineId);
				if(m==null){
					logger.error("can not find machine {} for instance {}",in.machineId,in.id);
				}else{
					in.machine=(m);
				}
				Application app=applicationMap.get(in.appId);
				if(app==null){
					logger.error("can not find application {} for instance {}",in.appId,in.id);
				}else{
					in.application=(app);
				}
				
				instanceMap.put(in.id,in);			
			});
		}else{
			logger.warn("can not find :"+configFile);
		}
	}
	//
	private static void setInstancePrioriy(){
		for(Instance i:getInstances()){
			i.priority=i.application.priority;
		}
	}
	//
	public static String renderTemplate(String instanceName){
		Instance instance=instanceMap.get(instanceName);
		if(instance==null){
			return null;
		}
		return renderTemplate(instance);
	}
	//
	//
	public static void saveTemplate(String appId,String value){
		String templateDir=workSpaceDir;
		templateDir+="template";
		File file=new File(templateDir+"/"+appId+".vm");
		try{
			if(!file.exists()){
				file.createNewFile();
			}
			FileUtil.saveContent(value, file);
		}catch(Exception e){
			logger.catching(e);
		}
	}
	//
	public static String getTemplate(String appId){
		String templateDir=workSpaceDir;
		templateDir+="template";
		File file=new File(templateDir+"/"+appId+".vm");
		if(!file.exists()){
			return null;
		}
		try {
			return FileUtil.getContent(file);
		} catch (IOException e) {
			logger.catching(e);
			return null;
		}
	}
	//
	private static String renderTemplate(Instance instance){
		VelocityContext ctx=new VelocityContext();
		ctx.put("instances",getInstances());
		ctx.put("instanceMap",instanceMap);
		ctx.put("machines",getMachines());
		ctx.put("machineMap",machineMap);
		ctx.put("applications",getApplications());
		ctx.put("applicationMap",applicationMap);
		ctx.put("instance", instance);
		//
		Map<String,String>properties=new HashMap<String, String>();
		properties.putAll(instance.properties);
		properties.putAll(instance.application.properties);
		properties.putAll(instance.machine.properties);
		//
		ctx.put("properties", properties);
		
		StringWriter sw=new StringWriter();
		String templateDir=workSpaceDir;
		templateDir+="template";
		
		File file=new File(templateDir+"/"+instance.appId+".vm");
		if(!file.exists()){
			logger.info("can not find {} use Default.vm to render",file);
			file=new File(templateDir+"/Default.vm");
		}
		if(!file.exists()){
			logger.warn("can not find template {}",file);
			return null;
		}
		Velocity.mergeTemplate(file.getPath(),"UTF-8", ctx, sw);
		return sw.toString();
	}
	//
	private static boolean testPort(String host, int port) {
		try {
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress(host, port), 1000);
			socket.close();
			return true;
		} catch (Exception ex) {
			return false;
		}
	}
	//
	public static void testMachine(Machine machine){
		machine.isAlive=(pingHost(machine.publicHost));
	}
	//
	//
	public static String runOnMachine(Machine m,String cmd){
		StringBuilder sb=new StringBuilder();
		sb.append("-------------------------------------------------------\n");
		sb.append("machine:"+m.id+"@"+m.privateHost+"\n");
		sb.append("cmd    :"+cmd+"\n");
		try{
			SshUtil.execute(
					m.privateHost,
					m.sshPort,
					m.sshUser,
					m.sshPassword,
					cmd,
					(out,err)->{
						sb.append("-------------------------------------------------------\n");
						sb.append(out+"\n");
						if(err!=null&&!err.isEmpty()){
							sb.append(err+"\n");			
						}
					});
		}catch(Exception e){
			sb.append(e.getMessage()+"\n");
		}
		return sb.toString();
	}
	//
	private static boolean pingHost(String host){
		try {
			return InetAddress.getByName(host).isReachable(3000);
		} catch (Exception e) {
			return false;
		}  
	}
	//
	//
	public static void testInstance(Instance instance){
		instance.isAlive=(testPort(
					instance.machine.publicHost,
					instance.port));
	}
	//
	private static String exec(Instance instance,String cmd){
		StringBuilder sb=new StringBuilder();
		sb.append("-------------------------------------------------------\n");
		sb.append("instance:"+instance.id+"\n");
		sb.append("cmd     :"+cmd+"\n");
		try{
			Machine m=instance.machine;
			SshUtil.execute(
					m.privateHost,
					m.sshPort,
					m.sshUser,
					m.sshPassword,
					cmd,
					(out,err)->{
						sb.append("-------------------------------------------------------\n");
						sb.append(out+"\n");
						if(err!=null&&!err.isEmpty()){
							sb.append(err+"\n");			
						}
					});
		}catch(Exception e){
			sb.append(e.getMessage()+"\n");
		}
		return sb.toString();
	}
	//
	public static void createInstance(Instance instance,String jsFile){
		if(instance.application.type.startsWith("jazmin")){
			String instanceDir=instance.machine.jazminHome+"/instance/"+instance.id;
			appendActionReport(exec(instance,
					"mkdir "+instanceDir));
			//
			jsFile=jsFile.replace('\"','\'');
			//
			appendActionReport(exec(instance,
					"echo \""+jsFile+"\" > "+instanceDir+"/jazmin.js"));
		}
	}
	//
	public static void startInstance(Instance instance){
		if(instance.application.type.startsWith("jazmin")){
			appendActionReport(exec(instance,instance.machine.jazminHome
				+"/jazmin startbg "+instance.id));
		}
		if(instance.application.type.equals(Application.TYPE_MEMCACHED)){
			appendActionReport(exec(instance,instance.machine.memcachedHome
				+"/memctl start "+instance.id));
		}
		if(instance.application.type.equals(Application.TYPE_HAPROXY)){
			appendActionReport(exec(instance,instance.machine.haproxyHome
				+"/hactl start "+instance.id));
		}
	}
	//
	public static void stopInstance(Instance instance){
		if(instance.application.type.startsWith("jazmin")){
			appendActionReport(exec(instance,instance.machine.jazminHome
					+"/jazmin stop "+instance.id));
		}
		if(instance.application.type.equals(Application.TYPE_MEMCACHED)){
			appendActionReport(exec(instance,instance.machine.memcachedHome
				+"/memctl stop "+instance.id));
		}
		if(instance.application.type.equals(Application.TYPE_HAPROXY)){
			appendActionReport(exec(instance,instance.machine.haproxyHome
				+"/hactl stop "+instance.id));
		}
	}
	public static String getTailLog(Instance instance) {
		if(instance.application.type.startsWith("jazmin")){
			return exec(instance,
							"tail -n 100 "+
							instance.machine.jazminHome+"/log/"+
							instance.id+".log");
		}
		return "not support instance type :"+instance.application.type;
	}
	//
	public static String getTailLog(String instanceName){
		Instance instance=instanceMap.get(instanceName);
		if(instance==null){
			return null;
		}
		
		return getTailLog(instance);
	}
	/**
	 * 
	 */
	public static AppPackage getInstancePackage(String instanceId) {
		Instance ins=instanceMap.get(instanceId);
		if(ins==null){
			logger.warn("can not find instance {}",instanceId);
			return null;
		}
		String suffex="";
		suffex=".jaz";
		if(ins.application.type.equals(Application.TYPE_JAZMIN_WEB)){
			suffex=".war";
		}
		String packageName=ins.appId+"-"+ins.packageVersion+suffex;
		AppPackage p= packageMap.get(packageName);
		logger.info("return package {} - {}",p,packageName);
		return p;
	}
	//
	public static AppPackage getPackage(String name){
		return packageMap.get(name);
	}
	//
	public static void resetActionReport(){
		actionReport=new StringBuffer();
	}
	//
	public static String actionReport(){
		return actionReport.toString();
	}
	//
	public static void appendActionReport(String content){
		actionReport.append(content+"\n");
	}
	//
	public static String renderApplicationGraph(String system){
		return graphVizRenderer.renderInstanceGraph(system,"");
	}
	//
	public static String renderInstanceGraph(String system,String cluster){
		return graphVizRenderer.renderInstanceGraph(system, cluster);
	}
	//--------------------------------------------------------------------------
	public static void main(String[] args)throws Exception{
		String s="include(\"http://www.baidu.com\")";
		System.out.println(s.replace('\"', '\''));
		//DeployManager.reload();
		//DeployManager.renderTemplate("MddsSystem");
	}
}
