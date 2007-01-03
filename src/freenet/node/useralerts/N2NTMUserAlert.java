/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.text.DateFormat;
import java.util.Date;

import freenet.node.PeerNode;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;

// Node To Node Text Message User Alert
public class N2NTMUserAlert implements UserAlert {
	private boolean isValid=true;
	private PeerNode sourcePeerNode;
	private String sourceNodename;
	private String targetNodename;
	private String messageText;
	private int fileNumber;
	private long composedTime;
	private long sentTime;
	private long receivedTime;

	public N2NTMUserAlert(PeerNode sourcePeerNode, String source, String target, String message, int fileNumber, long composedTime, long  sentTime, long receivedTime) {
		this.sourcePeerNode = sourcePeerNode;
		this.sourceNodename = source;
		this.targetNodename = target;
		this.messageText = message;
		this.fileNumber = fileNumber;
		this.composedTime = composedTime;
		this.sentTime = sentTime;
		this.receivedTime = receivedTime;
		isValid=true;
	}
	
	public boolean userCanDismiss() {
		return true;
	}

	public String getTitle() {
		return "Node To Node Text Message "+fileNumber+" from "+sourcePeerNode.getName()+" ("+sourcePeerNode.getPeer()+ ')';
	}
	
	public String getText() {
	  String messageTextBuf = HTMLEncoder.encode(messageText);
	  int j = messageTextBuf.length();
		StringBuffer messageTextBuf2 = new StringBuffer(j);
		for (int i = 0; i < j; i++) {
		  char ch = messageTextBuf.charAt(i);
		  if(ch == '\n')
		    messageTextBuf2.append("<br />");
		  else
		    messageTextBuf2.append(ch);
		}
		String replyString = "<a href=\"/send_n2ntm/?peernode_hashcode="+sourcePeerNode.hashCode()+"\">Reply</a><br /><br />";
		String s;
		s = "From: &lt;"+HTMLEncoder.encode(sourceNodename)+"&gt;<br />To: &lt;"+HTMLEncoder.encode(targetNodename)+"&gt;<hr /><br /><br />"+messageTextBuf2+"<br /><br />"+replyString;
		return s;
	}

	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("p", "From: " + sourceNodename + " (composed: " + DateFormat.getInstance().format(new Date(composedTime)) + "/sent: " + DateFormat.getInstance().format(new Date(sentTime)) + "/received: " + DateFormat.getInstance().format(new Date(receivedTime)) + ')');
		String[] lines = messageText.split("\n");
		for (int i = 0, c = lines.length; i < c; i++) {
			alertNode.addChild("div", lines[i]);
		}
		alertNode.addChild("p").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + sourcePeerNode.hashCode(), "Reply");
		return alertNode;
	}

	public short getPriorityClass() {
		return UserAlert.MINOR;
	}

	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		if(userCanDismiss()) isValid=b;
	}
	
	public String dismissButtonText(){
		return "Delete";
	}
	
	public boolean shouldUnregisterOnDismiss() {
		return true;
	}
	
	public void onDismiss() {
		sourcePeerNode.deleteExtraPeerDataFile(fileNumber);
	}
}
