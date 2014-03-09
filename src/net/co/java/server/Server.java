package net.co.java.server;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import net.co.java.entity.Entity;
import net.co.java.entity.Player;
import net.co.java.model.AccessException;
import net.co.java.model.AuthorizationPromise;
import net.co.java.model.Model;
import net.co.java.packets.Character_Creation_Packet;
import net.co.java.packets.GeneralData;
import net.co.java.packets.IncomingPacket;
import net.co.java.packets.InteractPacket;
import net.co.java.packets.ItemUsage;
import net.co.java.packets.MessagePacket;
import net.co.java.packets.PacketType;
import net.co.java.packets.PacketWriter;
import net.co.java.packets.MessagePacket.MessageType;

/**
 * The server is the main class for the Conquer Online server. 
 * It initialises one GameServer and one AuthServer.
 * 
 * @author Thomas Gmelig Meyling
 * @author Jan-Willem Gmelig Meyling
 *
 */
@Root
public class Server {
	
	@Element(name="model") private final Model model;
	private final AuthServer authServer;
	private final GameServer gameServer;
	@Attribute(name="gameport") private static final int GAME_PORT = 5816;
	@Attribute(name="authport") private static final int AUTH_PORT = 9958;
	
	/**
	 * Construct a new Server
	 * @throws IOException
	 */
	public Server(@Element(name="model") Model model) throws IOException {
		this.model = model;
		this.authServer = new AuthServer();
		this.gameServer = new GameServer();
	}
	
	/**
	 * @throws IOException
	 * @see net.co.java.server.Server.AuthServer#close()
	 */
	public void close() throws IOException {
		authServer.close();
		gameServer.close();
	}

	/**
	 * The Authentication Server handles user login requests
	 * and allows the client to connect to the Game Server.
	 * The Authentication Server listens at port 9958 by
	 * default.  
	 * @author Jan-Willem Gmelig Meyling
	 * @author Thomas Gmelig Meyling
	 */
	public class AuthServer implements Runnable {
		
		private final ServerSocket socket;
		
		/**
		 * Construct a new AuthServer listening at the default
		 * port (9958). Starts listening automatically from a
		 * new thread.
		 * @throws IOException
		 */
		AuthServer() throws IOException {
			socket = new ServerSocket(AUTH_PORT);
			new Thread(this).start();
			System.out.println("Authentication server listening on port " + AUTH_PORT);
		}

		@Override
		public void run() {
			for(;;) {
				// Wait for an incoming connection
				// While no incoming connection is available,
				// this method blocks.
				try {
					Socket client = socket.accept();
					new Client(client);
				} catch (IOException e) {
					// Catch it here, so the server won't stop
					// when a client disconnects
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * @throws IOException
		 * @see java.net.ServerSocket#close()
		 */
		public void close() throws IOException {
			socket.close();
		}

		/**
		 * The {@code Client} is the worker thread for every client listening to
		 * the {@code AuthServer}. It listens for incoming packets and works
		 * the queue of outgoing packets. All packets are delegated to their
		 * corresponding handler here.
		 * @author Jan-Willem Gmelig Meyling
		 * @author Thomas Gmelig Meyling
		 */
		public class Client extends ServerThread {
			
			Client(Socket client) throws IOException {
				super(client);
			}

			@Override
			public void handle(IncomingPacket packet) throws AccessException {
				switch(packet.getPacketType()) {
				case AUTH_LOGIN_PACKET:
					AuthLogin(packet);
					break;
				case AUTH_LOGIN_RESPONSE:
					AuthLoginResponse(packet);
					break;
				default:
					break;
				}
			}			

			/**
			 * When the user logs in, an Auth Login packet is sent from the client.
			 * In this function we prepare the response and check if the credentials
			 * are correct.
			 * @param packet
			 * @throws AccessException 
			 */
			private void AuthLogin(IncomingPacket packet) throws AccessException {
				String accountName	= packet.readString(4,16).replaceAll("[\u0000]", "");
				String password	= packet.readPassword().replaceAll("[\u0000]", "");
				String serverName	= packet.readString(36, 16).replaceAll("[\u0000]", "");
				
				if (model.isAuthorised(serverName, accountName, password)) {
					PacketWriter pw = new PacketWriter(PacketType.AUTH_LOGIN_FORWARD, 0x20);
					AuthorizationPromise promise = model.createAuthorizationPromise(accountName);
					Long identity = promise.getIdentity();
					long token = promise.getToken(); // SUCCESS
					pw.putUnsignedInteger(identity);
					pw.putUnsignedInteger(token);
					pw.putString("127.000.000.001", 16);
					pw.putUnsignedInteger(GAME_PORT);
					pw.send(this);
				} else {
					// TODO Send a response to the client
				}
			}
			
			/**
			 * The relatively simple AuthLoginResponse packet is handled here
			 * @param packet
			 */
			private void AuthLoginResponse(IncomingPacket packet) {
				long identity = packet.readUnsignedInt(4);
				long resNumber = packet.readUnsignedInt(8);
				String resLocation = packet.readString(12,16);
				System.out.println("ALR: " + resLocation + " " + identity + ", "  + resNumber);
			}
			
		}
	}
	
	/**
	 * The game server contains all required data for the entities and ensures
	 * that updates of these data - for example equipment or location - are
	 * efficiently broadcasted to all the connected clients. 
	 * @author Jan-Willem Gmelig Meyling
	 * @author Thomas Gmelig Meyling
	 */
	public class GameServer implements Runnable {
		
		private volatile int AMOUNT_OF_PLAYERS = 0;

		private final ServerSocket socket;
		
		/**
		 * Construct a new AuthServer listening at the default
		 * port (9958). Starts listening automatically from a
		 * new thread.
		 * @throws IOException
		 */
		GameServer() throws IOException {
			this.socket = new ServerSocket(GAME_PORT);
			new Thread(this).start();
			System.out.println("Game server listening on port " + GAME_PORT);
		}

		@Override
		public void run() {
			for(;;) {
				// Wait for an incoming connection
				// While no incoming connection is available,
				// this method blocks.
				try {
					Socket client = socket.accept();
					new Client(client);
				} catch (IOException e) {
					// Catch it here, so the server won't stop
					// when a client disconnects
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * @return the {@code Server} instance for this {@code GameServer}
		 */
		public Server getServer() {
			return Server.this;
		}
		
		/**
		 * @return the amount of online players
		 */
		public int getAmountOfPlayers() {
			return AMOUNT_OF_PLAYERS;
		}
		
		/**
		 * @return the address to which this Gameserver is bound
		 * @see java.net.ServerSocket#getInetAddress()
		 */
		public InetAddress getInetAddress() {
			return socket.getInetAddress();
		}

		/**
		 * @return the port number to which this Gameserver 
		 * @see java.net.ServerSocket#getLocalPort()
		 */
		public int getLocalPort() {
			return socket.getLocalPort();
		}

		/**
		 * @throws IOException
		 * @see java.net.ServerSocket#close()
		 */
		public void close() throws IOException {
			socket.close();
		}

		/**
		 * The {@code Client} is the worker thread for every client listening to
		 * the {@code GameServer}. It listens for incoming packets and works
		 * the queue of outgoing packets. All packets are delegated to their
		 * corresponding handler here.
		 * @author Jan-Willem Gmelig Meyling
		 * @author Thomas Gmelig Meyling
		 */
		public class Client extends ServerThread {
			
			private long identity;
			private Player player;
			

			Client(Socket client) throws IOException {
				super(client);
			}
			
			@Override
			protected void connected() {
				AMOUNT_OF_PLAYERS++;
				System.out.println("Amount of players: " + AMOUNT_OF_PLAYERS);
			}
			
			@Override
			protected void disconnected() {
				AMOUNT_OF_PLAYERS--;
				System.out.println("Amount of players: " + AMOUNT_OF_PLAYERS);
				if ( player != null ) {
					player.getLocation().getMap().removeEntity(player);
				}
			}
			
			/**
			 * @return the current GameServer instance
			 */
			public GameServer getGameServer() {
				return GameServer.this;
			}
			
			/**
			 * @return the model for this server
			 */
			public Model getModel() {
				return model;
			}
			
			/**
			 * @return the identity for this Client
			 */
			public long getIdentity() {
				return identity;
			}
			
			/**
			 * @return the player for this Client
			 */
			public Player getPlayer() {
				return player;
			}
			
			@Override
			public void handle(IncomingPacket packet) throws AccessException {
				switch(packet.getPacketType()) {
				case AUTH_LOGIN_RESPONSE:
					// Read the identity and token from the packet
					// and set these as keys for the cipher
					identity = packet.readUnsignedInt(4);
					AuthorizationPromise promise = model.getAuthorizationPromise(identity);
			
					long token = packet.readUnsignedInt(8);
					this.setKeys(token, identity);
					
					// Inform the client that the login was successful
					if (promise.hasCharacter())
					{		
						// Create the Entity object for the player, bound to
						// the current identity and client thread
						player = model.loadPlayer(promise);
						player.setClient(this);
						new MessagePacket(MessagePacket.SYSTEM, MessagePacket.ALL_USERS, "ANSWER_OK")
								.setMessageType(MessageType.LoginInfo)
								.build().send(this);
						// Send the character information packet
						player.characterInformation().send(this);
					}
					else
					{
						new MessagePacket(MessagePacket.SYSTEM, MessagePacket.ALL_USERS, "NEW_ROLE")
						.setMessageType(MessageType.LoginInfo)
						.build().send(this);
					}
					break;
				case ENTITY_MOVE_PACKET:
					for(Player p : player.getLocation().getMap().getPlayersInRange(player, true)) {
						packet.send(p.getClient());
					}
					player.walk(packet.readUnsignedByte(8));
					break;
				case GENERAL_DATA_PACKET:
					new GeneralData(packet).handle(this);
					break;
				case ITEM_USAGE_PACKET:
					new ItemUsage(packet).handle(this);
					break;
				case MESSAGE_PACKET:
					MessagePacket mp = new MessagePacket(packet);
					if(mp.getMessage().startsWith("/")) {
						new Command(mp).handle(this);
					} else {
						System.out.println(mp.getFrom() + " said " + mp.getMessage() + ".");
					}
					break;
				case CHARACTER_CREATION_PACKET:
					if (model.createCharacter(new Character_Creation_Packet(packet)))
					{
						new MessagePacket(MessagePacket.SYSTEM, MessagePacket.ALL_USERS, "ANSWER_OK")
						.setMessageType(MessageType.LoginInfo)
						.build().send(this);
						this.close();
					}
					else
					{
						new MessagePacket(MessagePacket.SYSTEM, MessagePacket.ALL_USERS, "Failed to create character. Character name already in use.")
						.setMessageType(MessageType.Dialog)
						.build().send(this);
					}
					break;
				case INTERACT_PACKET:
					new InteractPacket(packet).handle(this);
					break;
				default: 	
					System.out.println("Unimplemented " + packet.getPacketType().toString());
					break;
				}
			}
		}	
	}

	/**
	 * The Map enum contains all Maps for the current Server
	 * Map can be accessed by name (Map.CentralPlain) or id (1002).
	 * Maps should be instantiated only once and therefore the Enum
	 * singleton pattern is used. Each Map contains a Collection of
	 * Entities, and has a constructor for Locations that are in this
	 * Map.
	 * @author Jan-Willem Gmelig Meyling
	 * @author Thomas Gmelig Meyling
	 */
	public enum Map {
		CentralPlain(1002);
		
		private final Integer id;
		
		private final List<Entity> entities = new CopyOnWriteArrayList<Entity>();
	
		/**
		 * Construct a new Map with a given id
		 * @param id
		 */
		private Map(int id) {
			this.id = Integer.valueOf(id);
			System.out.println("Initializing map: " + this.toString() + " (" + this.id + ")");
		}
		
		/**
		 * @return the id for a Map
		 */
		public int getMapID() {
			return id;
		}
		
		/**
		 * Add an {@code Entity} to this Map, for example due a windspell, portal or spawn.
		 * Send an EntitySpawn packet to surrounding players.
		 * @param entity
		 */
		public void addEntity(Entity entity) {
			if(!entities.contains(entity)) {
				entities.add(entity);
			}
		}
		
		/**
		 * Remove an {@code Entity} from this Map - death or teleport.
		 * Send an Entity Remove packet to surrounding players.
		 * @param entity
		 */
		public void removeEntity(Entity entity) {
			if(entities.remove(entity));
				entity.removeEntity().sendTo(getPlayersInRange(entity, false));
		}
		
		/**
		 * @return all entities in this map
		 */
		public List<Entity> getEntities() {
			return entities;
		}

		
		/**
		 * @param me
		 * @return all entities within the default range excluding me
		 */
		public List<Entity> getEntitiesInRange(Entity me) {
			List<Entity> result = new ArrayList<Entity>();
			
			for ( Entity e : entities ) {
				if ( e == me || e.equals(me) )
					continue;
				else if (e.getLocation().inView(me.getLocation()))
					result.add(e);
			}
			
			return result;
		}
		
		/**
		 * @param me
		 * @return all players within the default range excluding me
		 */
		public List<Player> getPlayersInRange(Entity me, boolean includeme) {
			List<Player> result = new ArrayList<Player>();
			
			for ( Entity e : entities ) {
				if( e instanceof Player) {
					if(e != me || includeme ) {
						result.add((Player) e);
					}
				}
			}
			
			return result;
		}
		
	}

	/**
	 * Load the configuration file
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		Serializer serializer = new Persister();
		File source = new File("config.xml");
		serializer.read(Server.class, source);
	}

}
