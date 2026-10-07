"""Generate pieces/*.yml manifests + frontend apps catalog from one table.

Usage: python pieces/generate.py
Writes: backend/src/main/resources/pieces/<app>.yml, frontend/src/apps.ts
Auth types: none | bearer | header | query | basic | urlToken | connectionString | oauth2
- bearer:  Authorization: <prefix> <connection>   (prefix default "Bearer")
- header:  <header>: <prefix?><connection>
- query:   ?<queryParam>=<connection>  (or queryParams: [k1, k2] split "v1:v2")
- urlToken: connection interpolated into baseUrl as {{connection}}
- oauth2:  disabled stub until the OAuth slice lands
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIECES_DIR = os.path.join(ROOT, "backend", "src", "main", "resources", "pieces")
APPS_TS = os.path.join(ROOT, "frontend", "src", "apps.ts")

# (id, display, method, path, params, extra?)  extra: body= / baseUrl=
def op(id, display, method, path, params=(), **kw):
    return {"id": id, "displayName": display, "method": method, "path": path,
            "params": list(params), **kw}

def trig(id, display, method, path, params=(), cursor="id"):
    return {"id": id, "displayName": display, "method": method, "path": path,
            "params": list(params), "cursor": cursor}

def app(app, display, category, auth, base, ops, triggers=(), enabled=True, headers=None):
    return {"app": app, "displayName": display, "category": category,
            "enabled": enabled, "auth": auth, "baseUrl": base,
            "headers": headers or {}, "operations": ops, "triggers": list(triggers)}

B = lambda prefix="Bearer": {"type": "bearer", "prefix": prefix}
H = lambda header, prefix="": {"type": "header", "header": header, **({"prefix": prefix} if prefix else {})}
Q = lambda *params: {"type": "query", "queryParams": list(params)} if len(params) > 1 else {"type": "query", "queryParam": params[0]}
BASIC = {"type": "basic"}
NONE = {"type": "none"}
URLTOKEN = {"type": "urlToken"}
CONNSTR = {"type": "connectionString"}
OAUTH2 = {"type": "oauth2"}

APPS = [
 # ---------------- AI / LLM ----------------
 app("openai", "OpenAI", "ai", B(), "https://api.openai.com/v1",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat completion", "POST", "/chat/completions", ["model"])]),
 app("anthropic", "Anthropic Claude", "ai", H("x-api-key"), "https://api.anthropic.com/v1",
     [op("list_models", "List models", "GET", "/v1/models"),
      op("message", "Create message", "POST", "/v1/messages", ["model"])],
     headers={"anthropic-version": "2023-06-01"}),
 app("gemini", "Google Gemini", "ai", Q("key"), "https://generativelanguage.googleapis.com/v1beta",
     [op("list_models", "List models", "GET", "/v1beta/models"),
      op("generate", "Generate content", "POST", "/v1beta/models/{{model}}:generateContent", ["model"])]),
 app("groq", "Groq", "ai", B(), "https://api.groq.com/openai/v1",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat completion", "POST", "/chat/completions", ["model"])]),
 app("mistral", "Mistral", "ai", B(), "https://api.mistral.ai/v1",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat completion", "POST", "/chat/completions", ["model"])]),
 app("cohere", "Cohere", "ai", B(), "https://api.cohere.ai/v1",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat", "POST", "/v2/chat", ["model"])]),
 app("huggingface", "Hugging Face", "ai", B(), "https://api-inference.huggingface.co",
     [op("infer", "Run inference", "POST", "/models/{{model}}", ["model"])]),
 app("perplexity", "Perplexity", "ai", B(), "https://api.perplexity.ai",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat completion", "POST", "/chat/completions", ["model"])]),
 app("azure_openai", "Azure OpenAI", "ai", H("api-key"), "https://{{resource}}.openai.azure.com",
     [op("chat", "Chat completion", "POST",
         "/openai/deployments/{{deployment}}/chat/completions?api-version=2024-10-01",
         ["resource", "deployment"])]),
 app("ollama", "Ollama", "ai", NONE, "http://127.0.0.1:11434",
     [op("tags", "List models", "GET", "/api/tags"),
      op("generate", "Generate", "POST", "/api/generate", ["model", "prompt"])]),
 app("openrouter", "OpenRouter", "ai", B(), "https://openrouter.ai/api/v1",
     [op("list_models", "List models", "GET", "/models"),
      op("chat", "Chat completion", "POST", "/chat/completions", ["model"])]),
 app("bedrock", "AWS Bedrock", "ai", CONNSTR, "https://bedrock-runtime.{{region}}.amazonaws.com",
     [op("invoke", "Invoke model", "POST", "/model/{{model}}/invoke", ["region", "model"])]),
 # ---------------- Communication ----------------
 app("slack", "Slack", "communication", B(), "https://slack.com/api",
     [op("post_message", "Post message", "POST", "/chat.postMessage", ["channel", "text"]),
      op("list_channels", "List channels", "GET", "/conversations.list")],
     [trig("new_message", "New message", "GET", "/conversations.history", ["channel"], "ts")]),
 app("discord", "Discord", "communication", B(), "https://discord.com/api/v10",
     [op("send_message", "Send message", "POST", "/channels/{{channel}}/messages", ["channel", "content"]),
      op("get_channel", "Get channel", "GET", "/channels/{{channel}}", ["channel"])]),
 app("telegram", "Telegram", "communication", URLTOKEN, "https://api.telegram.org/bot{{connection}}",
     [op("send_message", "Send message", "POST", "/sendMessage", ["chat_id", "text"])],
     [trig("updates", "New update", "GET", "/getUpdates", cursor="update_id")]),
 app("whatsapp", "WhatsApp Business", "communication", B(), "https://graph.facebook.com/v20.0",
     [op("send_message", "Send message", "POST", "/{{phone_id}}/messages", ["phone_id"])]),
 app("teams", "MS Teams", "communication", URLTOKEN, "{{connection}}",
     [op("post_message", "Post message", "POST", "/", ["text"])]),
 app("twilio", "Twilio", "communication", BASIC, "https://api.twilio.com/2010-04-01",
     [op("send_sms", "Send SMS", "POST", "/Accounts/{{account}}/Messages.json", ["account", "To", "Body"])]),
 app("sendgrid", "SendGrid", "communication", B(), "https://api.sendgrid.com/v3",
     [op("send_mail", "Send mail", "POST", "/mail/send", ["to", "subject"]),
      op("list_templates", "List templates", "GET", "/templates")]),
 app("smtp", "SMTP", "communication", CONNSTR, "smtp://{{connection}}",
     [op("send_email", "Send email", "POST", "/send", ["to", "subject"])]),
 app("mattermost", "Mattermost", "communication", B(), "https://{{server}}/api/v4",
     [op("create_post", "Create post", "POST", "/posts", ["server", "channel_id", "message"]),
      op("list_channels", "List channels", "GET", "/teams/{{team}}/channels", ["server", "team"])]),
 app("gmail", "Gmail", "communication", OAUTH2, "https://gmail.googleapis.com/gmail/v1",
     [op("list_messages", "List messages", "GET", "/users/me/messages")], enabled=False),
 app("outlook", "Outlook", "communication", OAUTH2, "https://graph.microsoft.com/v1.0",
     [op("list_messages", "List messages", "GET", "/me/messages")], enabled=False),
 # ---------------- Databases (P2 drivers, connectionString) ----------------
 app("postgres", "PostgreSQL", "database", CONNSTR, "postgres://{{connection}}",
     [op("query", "Run query", "POST", "/query", ["sql"])]),
 app("mysql", "MySQL", "database", CONNSTR, "mysql://{{connection}}",
     [op("query", "Run query", "POST", "/query", ["sql"])]),
 app("mongodb", "MongoDB", "database", CONNSTR, "mongodb://{{connection}}",
     [op("find", "Find documents", "POST", "/find", ["collection"])]),
 app("redis", "Redis", "database", CONNSTR, "redis://{{connection}}",
     [op("get", "Get key", "POST", "/get", ["key"])]),
 app("sqlite", "SQLite", "database", CONNSTR, "file://{{connection}}",
     [op("query", "Run query", "POST", "/query", ["sql"])]),
 app("mssql", "SQL Server", "database", CONNSTR, "mssql://{{connection}}",
     [op("query", "Run query", "POST", "/query", ["sql"])]),
 # ---------------- Cloud / Storage ----------------
 app("dropbox", "Dropbox", "storage", B(), "https://api.dropboxapi.com/2",
     [op("list_folder", "List folder", "POST", "/files/list_folder", ["path"]),
      op("get_metadata", "Get metadata", "POST", "/files/get_metadata", ["path"]),
      op("upload", "Upload file", "POST", "/files/upload", ["path"],
         baseUrl="https://content.dropboxapi.com/2")]),
 app("box", "Box", "storage", B(), "https://api.box.com/2.0",
     [op("list_folder", "List folder", "GET", "/folders/{{id}}/items", ["id"]),
      op("get_file", "Get file", "GET", "/files/{{id}}", ["id"])]),
 app("s3", "AWS S3", "storage", CONNSTR, "https://s3.{{region}}.amazonaws.com",
     [op("list_objects", "List objects", "GET", "/{{bucket}}?list-type=2", ["region", "bucket"]),
      op("get_object", "Get object", "GET", "/{{bucket}}/{{key}}", ["region", "bucket", "key"])]),
 app("azure_blob", "Azure Blob", "storage", CONNSTR, "https://{{account}}.blob.core.windows.net",
     [op("list_blobs", "List blobs", "GET", "/{{container}}?restype=container&comp=list",
         ["account", "container"])]),
 app("drive", "Google Drive", "storage", OAUTH2, "https://www.googleapis.com/drive/v3",
     [op("list_files", "List files", "GET", "/files")], enabled=False),
 app("sheets", "Google Sheets", "storage", OAUTH2, "https://sheets.googleapis.com/v4/spreadsheets",
     [op("read", "Read range", "GET", "/{{id}}/values/{{range}}", ["id", "range"])], enabled=False),
 app("docs", "Google Docs", "storage", OAUTH2, "https://docs.googleapis.com/v1/documents",
     [op("get_doc", "Get document", "GET", "/{{id}}", ["id"])], enabled=False),
 app("gcalendar", "Google Calendar", "storage", OAUTH2, "https://www.googleapis.com/calendar/v3/calendars",
     [op("list_events", "List events", "GET", "/{{id}}/events", ["id"])], enabled=False),
 app("onedrive", "OneDrive", "storage", OAUTH2, "https://graph.microsoft.com/v1.0",
     [op("list_root", "List root", "GET", "/me/drive/root/children")], enabled=False),
 app("gcs", "Google Cloud Storage", "storage", OAUTH2, "https://storage.googleapis.com/storage/v1",
     [op("list_objects", "List objects", "GET", "/b/{{bucket}}/o", ["bucket"])], enabled=False),
 # ---------------- Developer / Git ----------------
 app("github", "GitHub", "developer", B(), "https://api.github.com",
     [op("get_repo", "Get repository", "GET", "/repos/{{owner}}/{{repo}}", ["owner", "repo"]),
      op("list_issues", "List issues", "GET", "/repos/{{owner}}/{{repo}}/issues", ["owner", "repo"]),
      op("create_issue", "Create issue", "POST", "/repos/{{owner}}/{{repo}}/issues", ["owner", "repo", "title"])],
     [trig("new_issue", "New issue", "GET", "/repos/{{owner}}/{{repo}}/issues", ["owner", "repo"])],
     headers={"Accept": "application/vnd.github+json"}),
 app("gitlab", "GitLab", "developer", H("PRIVATE-TOKEN"), "https://gitlab.com/api/v4",
     [op("get_project", "Get project", "GET", "/projects/{{id}}", ["id"]),
      op("list_issues", "List issues", "GET", "/projects/{{id}}/issues", ["id"]),
      op("create_issue", "Create issue", "POST", "/projects/{{id}}/issues", ["id", "title"])],
     [trig("new_issue", "New issue", "GET", "/projects/{{id}}/issues", ["id"])]),
 app("bitbucket", "Bitbucket", "developer", BASIC, "https://api.bitbucket.org/2.0",
     [op("get_repo", "Get repository", "GET", "/repositories/{{workspace}}/{{repo}}", ["workspace", "repo"]),
      op("list_issues", "List issues", "GET", "/repositories/{{workspace}}/{{repo}}/issues",
          ["workspace", "repo"])]),
 app("jira", "Jira", "developer", BASIC, "https://{{site}}.atlassian.net/rest/api/3",
     [op("get_issue", "Get issue", "GET", "/issue/{{key}}", ["site", "key"]),
      op("create_issue", "Create issue", "POST", "/issue", ["site"])],
     [trig("new_issue", "New issue", "GET", "/search/jql?jql=created%3E-1d", ["site"], "id")]),
 app("linear", "Linear", "developer", B(), "https://api.linear.app/graphql",
     [op("list_issues", "List issues", "POST", "/graphql", [],
         body={"query": "{ issues { nodes { id title } } }"})]),
 app("trello", "Trello", "developer", Q("key", "token"), "https://api.trello.com/1",
     [op("get_board", "Get board", "GET", "/boards/{{id}}", ["id"]),
      op("create_card", "Create card", "POST", "/cards", ["idList", "name"])],
     [trig("new_card", "New card", "GET", "/lists/{{id}}/cards", ["id"])]),
 app("clickup", "ClickUp", "developer", H("Authorization"), "https://api.clickup.com/api/v2",
     [op("get_task", "Get task", "GET", "/task/{{id}}", ["id"]),
      op("create_task", "Create task", "POST", "/list/{{list}}/task", ["list", "name"])],
     [trig("new_task", "New task", "GET", "/list/{{list}}/task", ["list"])]),
 app("notion", "Notion", "developer", B(), "https://api.notionapi.com/v1",
     [op("query_db", "Query database", "POST", "/databases/{{id}}/query", ["id"]),
      op("create_page", "Create page", "POST", "/pages", [])],
     [trig("new_page", "New page", "POST", "/databases/{{id}}/query", ["id"], "id")],
     headers={"Notion-Version": "2022-06-13"}),
 # ---------------- Web / APIs ----------------
 app("graphql", "GraphQL", "web", URLTOKEN, "{{connection}}",
     [op("execute", "Execute query", "POST", "/", ["query"])]),
 app("rss", "RSS", "web", NONE, "{{connection}}",
     [op("get_feed", "Get feed", "GET", "/")]),
 app("soap", "SOAP", "web", URLTOKEN, "{{connection}}",
     [op("call", "Call action", "POST", "/", ["action"])],
     headers={"Content-Type": "text/xml"}),
 # ---------------- E-commerce ----------------
 app("shopify", "Shopify", "ecommerce", H("X-Shopify-Access-Token"),
     "https://{{shop}}.myshopify.com/admin/api/2024-10",
     [op("list_products", "List products", "GET", "/products.json", ["shop"]),
      op("create_product", "Create product", "POST", "/products.json", ["shop"])],
     [trig("new_product", "New product", "GET", "/products.json", ["shop"])]),
 app("woocommerce", "WooCommerce", "ecommerce", BASIC, "https://{{site}}/wp-json/wc/v3",
     [op("list_orders", "List orders", "GET", "/orders", ["site"]),
      op("create_product", "Create product", "POST", "/products", ["site", "name"])]),
 app("stripe", "Stripe", "ecommerce", B(), "https://api.stripe.com/v1",
     [op("list_customers", "List customers", "GET", "/customers"),
      op("create_payment", "Create payment", "POST", "/payment_intents", ["amount", "currency"])],
     [trig("new_customer", "New customer", "GET", "/customers")]),
 app("paypal", "PayPal", "ecommerce", BASIC, "https://api-m.paypal.com/v2",
     [op("get_order", "Get order", "GET", "/checkout/orders/{{id}}", ["id"])]),
 app("square", "Square", "ecommerce", B(), "https://connect.squareup.com/v2",
     [op("list_payments", "List payments", "GET", "/payments")]),
 app("magento", "Magento", "ecommerce", B(), "https://{{site}}/rest/V1",
     [op("list_orders", "List orders", "GET", "/orders", ["site"])]),
 # ---------------- Marketing / CRM ----------------
 app("hubspot", "HubSpot", "crm", B(), "https://api.hubapi.com",
     [op("list_contacts", "List contacts", "GET", "/crm/v3/objects/contacts"),
      op("create_contact", "Create contact", "POST", "/crm/v3/objects/contacts", ["email"])],
     [trig("new_contact", "New contact", "GET", "/crm/v3/objects/contacts")]),
 app("pipedrive", "Pipedrive", "crm", Q("api_token"), "https://{{company}}.pipedrive.com/api/v1",
     [op("list_deals", "List deals", "GET", "/deals", ["company"]),
      op("create_deal", "Create deal", "POST", "/deals", ["company", "title"])]),
 app("mailchimp", "Mailchimp", "crm", BASIC, "https://{{dc}}.api.mailchimp.com/3.0",
     [op("list_members", "List members", "GET", "/lists/{{list}}/members", ["dc", "list"]),
      op("add_member", "Add member", "POST", "/lists/{{list}}/members", ["dc", "list", "email_address"])]),
 app("brevo", "Brevo", "crm", H("api-key"), "https://api.brevo.com/v3",
     [op("send_email", "Send email", "POST", "/smtp/email", ["to", "subject"]),
      op("create_contact", "Create contact", "POST", "/contacts", ["email"])]),
 app("activecampaign", "ActiveCampaign", "crm", H("Api-Token"), "https://{{account}}.api-us1.com/api/3",
     [op("list_contacts", "List contacts", "GET", "/contacts", ["account"]),
      op("create_contact", "Create contact", "POST", "/contacts", ["account", "email"])]),
 app("klaviyo", "Klaviyo", "crm", B("Klaviyo-API-Key"), "https://a.klaviyo.com/api",
     [op("list_profiles", "List profiles", "GET", "/profiles"),
      op("create_profile", "Create profile", "POST", "/profiles", ["email"])],
     headers={"revision": "2024-10-15"}),
 app("intercom", "Intercom", "crm", B(), "https://api.intercom.io",
     [op("list_contacts", "List contacts", "GET", "/contacts"),
      op("create_contact", "Create contact", "POST", "/contacts", ["email"])],
     headers={"Intercom-Version": "2.11"}),
 app("salesforce", "Salesforce", "crm", OAUTH2, "https://{{instance}}.salesforce.com/services/data/v60.0",
     [op("query", "Query records", "GET", "/query", ["instance"])], enabled=False),
 app("zoho", "Zoho CRM", "crm", OAUTH2, "https://www.zohoapis.com/crm/v7",
     [op("list_contacts", "List contacts", "GET", "/Contacts")], enabled=False),
 # ---------------- Social (OAuth2 deferred) ----------------
 app("facebook", "Facebook", "social", OAUTH2, "https://graph.facebook.com/v20.0",
     [op("get_profile", "Get profile", "GET", "/me")], enabled=False),
 app("instagram", "Instagram", "social", OAUTH2, "https://graph.instagram.com/v20.0",
     [op("get_profile", "Get profile", "GET", "/me")], enabled=False),
 app("linkedin", "LinkedIn", "social", OAUTH2, "https://api.linkedin.com/v2",
     [op("get_profile", "Get profile", "GET", "/me")], enabled=False),
 app("x", "X (Twitter)", "social", OAUTH2, "https://api.twitter.com/2",
     [op("get_user", "Get user", "GET", "/users/me")], enabled=False),
 app("youtube", "YouTube", "social", OAUTH2, "https://www.googleapis.com/youtube/v3",
     [op("list_videos", "List videos", "GET", "/videos")], enabled=False),
 app("reddit", "Reddit", "social", OAUTH2, "https://oauth.reddit.com/api/v1",
     [op("get_profile", "Get profile", "GET", "/me")], enabled=False),
 app("tiktok", "TikTok", "social", OAUTH2, "https://open.tiktokapis.com/v2",
     [op("get_profile", "Get profile", "GET", "/user/info/")], enabled=False),
 # ---------------- Productivity ----------------
 app("zapier", "Zapier", "productivity", URLTOKEN, "{{connection}}",
     [op("trigger", "Fire hook", "POST", "/", [])]),
 app("airtable", "Airtable", "productivity", B(), "https://api.airtable.com/v0",
     [op("list_records", "List records", "GET", "/{{base}}/{{table}}", ["base", "table"]),
      op("create_record", "Create record", "POST", "/{{base}}/{{table}}", ["base", "table"])],
     [trig("new_record", "New record", "GET", "/{{base}}/{{table}}", ["base", "table"])]),
 app("calendly", "Calendly", "productivity", B(), "https://api.calendly.com",
     [op("list_events", "List events", "GET", "/scheduled_events")],
     [trig("new_event", "New event", "GET", "/scheduled_events", cursor="uri")]),
 app("todoist", "Todoist", "productivity", B(), "https://api.todoist.com/api/v1",
     [op("list_tasks", "List tasks", "GET", "/tasks"),
      op("create_task", "Create task", "POST", "/tasks", ["content"])]),
 app("asana", "Asana", "productivity", B(), "https://app.asana.com/api/1.0",
     [op("list_tasks", "List tasks", "GET", "/tasks"),
      op("create_task", "Create task", "POST", "/tasks", ["name"])]),
 app("monday", "Monday.com", "productivity", B(), "https://api.monday.com/v2",
     [op("list_boards", "List boards", "POST", "/v2", [],
         body={"query": "{ boards { id name } }"})]),
 app("excel", "MS Excel", "productivity", OAUTH2, "https://graph.microsoft.com/v1.0",
     [op("list_worksheets", "List worksheets", "GET", "/me/drive/items/{{id}}/workbook/worksheets", ["id"])],
     enabled=False),
 app("gsheets_app", "Google Sheets App", "productivity", OAUTH2,
     "https://sheets.googleapis.com/v4/spreadsheets",
     [op("read", "Read range", "GET", "/{{id}}/values/{{range}}", ["id", "range"])], enabled=False),
 # ---------------- Other ----------------
 app("homeassistant", "Home Assistant", "other", B(), "https://{{host}}/api",
     [op("get_states", "Get states", "GET", "/states", ["host"]),
      op("call_service", "Call service", "POST", "/services/{{domain}}/{{service}}",
          ["host", "domain", "service"])]),
 app("mqtt", "MQTT", "other", CONNSTR, "mqtt://{{connection}}",
     [op("publish", "Publish", "POST", "/publish", ["topic"])]),
 app("wordpress", "WordPress", "other", BASIC, "https://{{site}}/wp-json/wp/v2",
     [op("list_posts", "List posts", "GET", "/posts", ["site"]),
      op("create_post", "Create post", "POST", "/posts", ["site", "title"])]),
 app("steam", "Steam", "other", Q("key"), "https://api.steampowered.com",
     [op("get_player", "Get player", "GET", "/ISteamUser/GetPlayerSummaries/v0002", ["steamids"])]),
 app("plex", "Plex", "other", H("X-Plex-Token"), "https://{{server}}:32400",
     [op("list_libraries", "List libraries", "GET", "/library/sections", ["server"])]),
 app("hue", "Philips Hue", "other", URLTOKEN, "http://{{connection}}/api",
     [op("list_lights", "List lights", "GET", "/lights")]),
]

def yaml_str(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    s = str(v)
    if any(c in s for c in ":#{}[]&*!|>'\"%@`,") or s != s.strip() or "\n" in s or "{{" in s:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return s if s else '""'

def dump(manifest):
    L = [f"app: {manifest['app']}", f"displayName: {yaml_str(manifest['displayName'])}",
         f"category: {manifest['category']}", f"enabled: {yaml_str(manifest['enabled'])}", "auth:"]
    for k, v in manifest["auth"].items():
        if isinstance(v, list):
            L.append(f"  {k}: [{', '.join(v)}]")
        elif v is not None:
            L.append(f"  {k}: {yaml_str(v)}")
    L.append(f"baseUrl: {yaml_str(manifest['baseUrl'])}")
    if manifest["headers"]:
        L.append("headers:")
        for k, v in manifest["headers"].items():
            L.append(f"  {k}: {yaml_str(v)}")
    L.append("operations:")
    for o in manifest["operations"]:
        L.append(f"  - id: {o['id']}")
        L.append(f"    displayName: {yaml_str(o['displayName'])}")
        L.append(f"    method: {o['method']}")
        L.append(f"    path: {yaml_str(o['path'])}")
        if o.get("baseUrl"):
            L.append(f"    baseUrl: {yaml_str(o['baseUrl'])}")
        L.append(f"    params: [{', '.join(o['params'])}]")
        if o.get("body") is not None:
            import json as _j
            L.append(f"    body: {_j.dumps(o['body'])}")
    L.append("triggers:")
    for t in manifest["triggers"]:
        L.append(f"  - id: {t['id']}")
        L.append(f"    displayName: {yaml_str(t['displayName'])}")
        L.append(f"    method: {t['method']}")
        L.append(f"    path: {yaml_str(t['path'])}")
        L.append(f"    params: [{', '.join(t['params'])}]")
        L.append(f"    cursor: {t['cursor']}")
    return "\n".join(L) + "\n"

def main():
    os.makedirs(PIECES_DIR, exist_ok=True)
    for m in APPS:
        with open(os.path.join(PIECES_DIR, m["app"] + ".yml"), "w") as f:
            f.write(dump(m))
    with open(APPS_TS, "w") as f:
        f.write("export interface PieceApp {\n  app: string;\n  displayName: string;\n"
                "  category: string;\n  enabled: boolean;\n  operations: string[];\n  triggers: string[];\n}\n\n"
                "export const PIECE_APPS: PieceApp[] = [\n")
        for m in APPS:
            ops = ", ".join(f'"{o["id"]}"' for o in m["operations"])
            trs = ", ".join(f'"{t["id"]}"' for t in m["triggers"])
            f.write(f'  {{ app: "{m["app"]}", displayName: "{m["displayName"]}", '
                    f'category: "{m["category"]}", enabled: {str(m["enabled"]).lower()}, '
                    f'operations: [{ops}], triggers: [{trs}] }},\n')
        f.write("];\n")
    en = sum(1 for m in APPS if m["enabled"])
    print(f"wrote {len(APPS)} manifests ({en} enabled, {len(APPS)-en} oauth-deferred) + apps.ts")

if __name__ == "__main__":
    main()
