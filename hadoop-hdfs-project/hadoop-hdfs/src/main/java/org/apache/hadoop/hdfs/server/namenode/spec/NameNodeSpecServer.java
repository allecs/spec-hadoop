package org.apache.hadoop.hdfs.server.namenode.spec;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
public class NameNodeSpecServer {
  private static final Log LOG = NameNode.LOG;
  private static final Log stateChangeLog = NameNode.stateChangeLog;

  private final NameNode nn;
  private final FSNamesystem namesystem;
  private final NamenodeProtocols rpcServer;
  private final FSDirectory fsDirectory;

  private LinkedList<UpcallLog> upcallLogs;

  public NameNodeSpecServer(Configuration conf, NameNode nn) {
    this.nn = nn;
    this.rpcServer = nn.getRpcServer();
    this.namesystem = nn.getNamesystem();
    this.fsDirectory = this.namesystem.getFSDirectory();
    UpcallLog.setNn(nn);
    upcallLogs = new LinkedList<UpcallLog>();
  }

  public void start() {
    SpecServerCLib specServer = (SpecServerCLib) Native.loadLibrary("specServer", SpecServerCLib.class);
    specServer.run(new CommitUpcallWrapper(this), new ReplicaUpcallWrapper(this), new RollbackUpcallWrapper(this));
  }

  public void stop() {

  }

  public class ReplicaUpcallWrapper implements SpecServerCLib.ReplicaUpcall_t {
    private final NameNodeSpecServer parent;
    ReplicaUpcallWrapper(NameNodeSpecServer parent) {
      this.parent = parent;
    }
    public void invoke(long opnum, Pointer str1, Pointer str2) {
      str2.setString(0, parent.replicaUpcall(opnum, str1.getString(0)));
    }
  }
  public class RollbackUpcallWrapper implements SpecServerCLib.RollbackUpcall_t {
    private final NameNodeSpecServer parent;
    RollbackUpcallWrapper(NameNodeSpecServer parent) {
      this.parent = parent;
    }
    public void invoke(long current, long to) {
      this.parent.rollbackUpcall(current, to);
    }
  }

  public class CommitUpcallWrapper implements SpecServerCLib.CommitUpcall_t{
    private final NameNodeSpecServer parent;
    CommitUpcallWrapper(NameNodeSpecServer parent) {
      this.parent = parent;
    }
    public void invoke(long opnum) {
      parent.commitUpcall(opnum);
      //System.out.println("in java commit, " + opnum);
    }
  }

  /**
   * for read operations: return the result
   * for write operations:
   *   create a undo log record and append to a undo log,
   *   apply the op to the namespace, editlog it (no need to logsync)
   *   return the result
   * @param opnum operation number
   * @param param marshaled parameters
   * @return marshaled return value
   */
  public String replicaUpcall(long opnum, String param) {
    ReplicaUpcall.Request req = null;
    UpcallLog log;

    try {
      ReplicaUpcall.Request.Builder builder = ReplicaUpcall.Request.newBuilder();
      TextFormat.merge(param, builder);
      req = builder.build();
    } catch (TextFormat.ParseException e) {
      LOG.error("cannot parse parameters");
      e.printStackTrace();
      return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setException(e.getMessage()));
    }

    switch (req.getOp()) {

      case LS:
        try {
          DirectoryListing result = rpcServer.getListing(req.getSrc(), req.getStartAfter().toByteArray(), req.getNeedLocation());
          for (HdfsFileStatus status: result.getPartialListing()) {
            status.voidTimestamps();
            status.permissionInShort = status.getPermission().toShort();
          }
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setDirectoryListing(ByteString.
              copyFrom(SerializationUtils.serialize(result)))); // TODO need to check if serializing works
        } catch (IOException e) {
          e.printStackTrace();
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setException(e.getMessage()));
        }

      case MKDIR:
        log = new UpcallLog(opnum);
        upcallLogs.add(log);
        try {
          UpcallLog.getUpcallLogLock().lock();
          UpcallLog.currentOpLog = log;
          boolean result = rpcServer.mkdirs(req.getSrc(), new FsPermission((short) req.getMasked()), req.getCreateParent());
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setSuccess(result));
        } catch (IOException e) {
          e.printStackTrace();
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setException(e.getMessage()));
        } finally {
          UpcallLog.currentOpLog = null;
          UpcallLog.getUpcallLogLock().unlock();
        }

      case RM:
        log = new UpcallLog(opnum);
        upcallLogs.add(log);
        try {
          UpcallLog.getUpcallLogLock().lock();
          UpcallLog.currentOpLog = log;
          boolean result = rpcServer.delete(req.getSrc(), req.getRecursive());
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setSuccess(result));
        } catch (IOException e) {
          e.printStackTrace();
          return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setException(e.getMessage()));
        } finally {
          UpcallLog.currentOpLog = null;
          UpcallLog.getUpcallLogLock().unlock();
        }

      default:
        LOG.error("unknown op");
        return TextFormat.printToString(ReplicaUpcall.Reply.newBuilder().setException("unknown op"));
    }

  }

  /**
   * rollback by undoing the logs, editlog them (no need to logsync either)
   * @param current must be the latest opnum, right?...
   * @param to
   */
  public void rollbackUpcall(long current, long to) {
    Iterator<UpcallLog> iter = upcallLogs.descendingIterator();
    while (iter.hasNext()) {
      UpcallLog l = iter.next();
      if (l.getOpNum() >= to) {
        l.undo();
        iter.remove();
      } else {
        break;
      }
    }
  }

  /**
   * delete undo log records from begining to opnum
   * simply delete the undo logs
   * @param commitOpnum
   */
  public void commitUpcall(long commitOpnum) {
    Iterator<UpcallLog> iter = upcallLogs.iterator();
    while (iter.hasNext()) {
      UpcallLog l = iter.next();
      if (l.getOpNum() <= commitOpnum) {
        iter.remove();
      } else {
        break;
      }
    }
  }
}
