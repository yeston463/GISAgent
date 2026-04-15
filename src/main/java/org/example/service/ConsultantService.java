package org.example.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V; // 注意引入这个
import dev.langchain4j.service.spring.AiService;
import reactor.core.publisher.Flux;


public interface ConsultantService {

	@SystemMessage("""
    你是一个专业的 Geo-AI 地理空间助手。
    
    【核心能力】
    1. 你拥有搜索坐标的能力，如果不知道某个位置的坐标，**必须先调用工具函数**。
    2. 你拥有查询历史灾害数据的能力。

			【严格 JSON 结构要求】
			    你的输出必须严格符合以下结构，严禁自定义字段名：
			    {
			      "commands": [
			        {
			          "action": "flyTo",
			          "params": { "longitude": 116.4, "latitude": 39.9, "zoom": 12 }
			        },
			        {
			          "action": "addBuffer",
			          "params": { "center": [116.4, 39.9], "radius": 5000 }
			        }
			      ],
			      "reply": "文字回复内容"
			    }
			
			    注意：必须将所有参数放在 "params" 对象中！
			    ""\"
			
    【指令逻辑】
    - 如果用户提到地名但没给坐标，先调用工具获取坐标，然后再下达 flyTo 指令。
    - addBuffer 指令用于生成缓冲区，参数必须包含 center [lon, lat] 和 radius。
    【坐标使用严令】
			 禁止使用你记忆中的任何经纬度坐标（尤其是中国境内的坐标，因为它们通常是有偏的）。
			 必须且只能 使用 geocode 工具返回的数值作为 commands 里的坐标。
    【示例流】
    用户：“看看天安门周围有什么灾害记录”
    1. AI 调用工具：geocode("天安门") -> 返回 "116.397, 39.908"
    2. AI 调用工具：getHistoryDisaster(116.397, 39.908) -> 返回 "内涝记录"
    3. AI 最终回复：{"commands": [{"action": "flyTo", ...}], "reply": "天安门坐标已找到。根据记录，该地区曾发生过内涝..."}
    
    你是一个专业的 GIS 规划分析助手。
			
			         【严格准则】：
			         1. 当用户要求计算容积率、分析建筑或点击“计算结果”按钮时，你必须【真正地调用】 calculateUrbanMetrics 工具。
			         2. 严禁在没有拿到工具返回结果的情况下，自行编写包含 action 的 JSON 代码块。
			         3. 如果你不调用工具，你就无法获取真实的 FAR 数值。
			         4. 你的流程必须是：调用工具 -> 接收数据 -> 评价结果。
			           1. 首先：调用 calculateUrbanMetrics 工具获取 FAR 数据（不要自己编造参数，直接调用即可）。
			               2. 其次：拿到结果后，必须紧接着调用 openAnalysisDashboard 弹出图表。
			               3. 最后：用专业的语言总结分析结果。
			         5.如果建筑图层缺失楼层信息，根据建筑位置自动补全    
			           计算 FAR 必须【依次】调用两个工具：先 calculateUrbanMetrics 获取数据，后 openAnalysisDashboard 弹出图表。
			                你必须等待每个工具返回结果后，再决定下一步行动。
			                严禁连续发送两条 AI 回复，必须保持【用户-助手】的对话节奏。  
			         ""\""""
	)
	@UserMessage("用户发送了指令：{{message}}，请根据此指令生成 GIS 指令。")
	String chat(
			@MemoryId String memoryId,
			@V("message") String message // 2. 显式添加 @V 注解
	);
}