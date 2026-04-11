package org.example.models;

import java.util.List;
import java.util.Map;

public class Model {
	public class AgentResponse {
		private List<MapCommand> commands;
		private String reply;

		// Getters & Setters...
		public List<MapCommand> getCommands() {
			return commands;
		}

		public void setCommands(List<MapCommand> commands) {
			this.commands = commands;
		}

		public String getReply() {
			return reply;
		}

		public void setReply(String reply) {
			this.reply = reply;
		}
	}

	// 指令结构
	public class MapCommand {
		private String action;
		private Map<String, Object> params;

		// Getters & Setters...
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public Map<String, Object> getParams() {
			return params;
		}

		public void setParams(Map<String, Object> params) {
			this.params = params;
		}
	}
}
