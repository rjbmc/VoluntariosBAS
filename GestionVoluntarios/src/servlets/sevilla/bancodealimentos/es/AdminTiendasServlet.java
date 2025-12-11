package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 5L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SP_LIST_NAME = "Tiendas";
    private static final String SP_UUID_FIELD = "SqlRowUUID";

    public static class Tienda {
        public int codigo, prioridad, huecosTurno1, huecosTurno2, huecosTurno3, huecosTurno4;
        public String denominacion, direccion, lat, lon, cp, poblacion, supervisor, disponible;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null || !((boolean)session.getAttribute("isAdmin"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String supervisor = request.getParameter("supervisor");
        String zona = request.getParameter("zona");

        List<Tienda> tiendas = new ArrayList<>();
        StringBuilder sqlBuilder = new StringBuilder("SELECT codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Supervisor, disponible, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE 1=1");
        
        if (supervisor != null && !supervisor.isEmpty()) {
            sqlBuilder.append(" AND Supervisor = ?");
        }
        if (zona != null && !zona.isEmpty()) {
            sqlBuilder.append(" AND Zona = ?");
        }
        sqlBuilder.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
            
            int paramIndex = 1;
            if (supervisor != null && !supervisor.isEmpty()) {
                stmt.setString(paramIndex++, supervisor);
            }
            if (zona != null && !zona.isEmpty()) {
                stmt.setString(paramIndex++, zona);
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Tienda t = new Tienda();
                t.codigo = rs.getInt("codigo");
                t.denominacion = rs.getString("denominacion");
                t.direccion = rs.getString("Direccion");
                t.lat = rs.getString("Lat");
                t.lon = rs.getString("Lon");
                t.cp = rs.getString("cp");
                t.poblacion = rs.getString("Poblacion");
                t.supervisor = rs.getString("Supervisor");
                t.disponible = rs.getString("disponible");
                t.prioridad = rs.getInt("prioridad");
                t.huecosTurno1 = rs.getInt("HuecosTurno1");
                t.huecosTurno2 = rs.getInt("HuecosTurno2");
                t.huecosTurno3 = rs.getInt("HuecosTurno3");
                t.huecosTurno4 = rs.getInt("HuecosTurno4");
                tiendas.add(t);
            }
        } catch (SQLException e) {
            throw new ServletException("Error de base de datos al obtener tiendas", e);
        }

        objectMapper.writeValue(response.getWriter(), tiendas);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !((boolean)session.getAttribute("isAdmin"))) {
            sendJsonResponse(response, false, "Acceso denegado.", HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        if ("save".equals(action)) {
            try (Connection conn = DatabaseUtil.getConnection()) {
                conn.setAutoCommit(false);

                boolean isUpdate = Boolean.parseBoolean(request.getParameter("isUpdate"));
                int codigo = Integer.parseInt(request.getParameter("codigo"));
                String denominacion = request.getParameter("denominacion");
                String direccion = request.getParameter("direccion");
                String lat = request.getParameter("lat");
                String lon = request.getParameter("lon");
                String cp = request.getParameter("cp");
                String poblacion = request.getParameter("poblacion");
                String cadena = request.getParameter("cadena");
                String disponible = request.getParameter("disponible");
                int prioridad = Integer.parseInt(request.getParameter("prioridad"));
                int h1 = Integer.parseInt(request.getParameter("huecosTurno1"));
                int h2 = Integer.parseInt(request.getParameter("huecosTurno2"));
                int h3 = Integer.parseInt(request.getParameter("huecosTurno3"));
                int h4 = Integer.parseInt(request.getParameter("huecosTurno4"));

                if (isUpdate) {
                    String sqlUpdate = "UPDATE tiendas SET denominacion = ?, Direccion = ?, Lat = ?, Lon = ?, cp = ?, Poblacion = ?, Cadena = ?, disponible = ?, HuecosTurno1 = ?, HuecosTurno2 = ?, HuecosTurno3 = ?, HuecosTurno4 = ?, prioridad = ?, notificar = 'S' WHERE codigo = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                        stmt.setString(1, denominacion);
                        stmt.setString(2, direccion);
                        stmt.setString(3, lat);
                        stmt.setString(4, lon);
                        stmt.setString(5, cp);
                        stmt.setString(6, poblacion);
                        stmt.setString(7, cadena);
                        stmt.setString(8, disponible);
                        stmt.setInt(9, h1);
                        stmt.setInt(10, h2);
                        stmt.setInt(11, h3);
                        stmt.setInt(12, h4);
                        stmt.setInt(13, prioridad);
                        stmt.setInt(14, codigo);
                        stmt.executeUpdate();
                        LogUtil.logOperation(conn, "ADMIN_UPDATE_T", adminUser, "Actualizada tienda: " + codigo);
                    }
                } else {
                    String sqlInsert = "INSERT INTO tiendas (codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, prioridad, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S')";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlInsert)) {
                        stmt.setInt(1, codigo);
                        stmt.setString(2, denominacion);
                        stmt.setString(3, direccion);
                        stmt.setString(4, lat);
                        stmt.setString(5, lon);
                        stmt.setString(6, cp);
                        stmt.setString(7, poblacion);
                        stmt.setString(8, cadena);
                        stmt.setString(9, disponible);
                        stmt.setInt(10, h1);
                        stmt.setInt(11, h2);
                        stmt.setInt(12, h3);
                        stmt.setInt(13, h4);
                        stmt.setInt(14, prioridad);
                        stmt.executeUpdate();
                        LogUtil.logOperation(conn, "ADMIN_CREATE_T", adminUser, "Creada tienda: " + codigo);
                    }
                }

                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("Title", denominacion);
                    spData.put("Direccion", direccion);
                    spData.put("Latitud", lat);
                    spData.put("Longitud", lon);
                    spData.put("CP", cp);
                    spData.put("Poblacion", poblacion);
                    spData.put("Cadena", cadena);
                    spData.put("Disponible", disponible);
                    spData.put("Prioridad", prioridad);
                    spData.put("HuecosTurno1", h1);
                    spData.put("HuecosTurno2", h2);
                    spData.put("HuecosTurno3", h3);
                    spData.put("HuecosTurno4", h4);

                    String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_NAME);
                     if (listId == null) {
                        throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");
                    }

                    String rowUuid = String.valueOf(codigo);

                    if (isUpdate) {
                        String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listId, SP_UUID_FIELD, rowUuid);
                        if (itemId != null) {
                            FieldValueSet fieldsToUpdate = new FieldValueSet();
                            fieldsToUpdate.setAdditionalData(spData);
                            SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listId, itemId, fieldsToUpdate);
                            LogUtil.logOperation(conn, "SP_UPDATE_T", adminUser, "SP actualizado para tienda: " + codigo);
                        } else {
                            spData.put(SP_UUID_FIELD, rowUuid);
                            FieldValueSet fieldsToCreate = new FieldValueSet();
                            fieldsToCreate.setAdditionalData(spData);
                            SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fieldsToCreate);
                            LogUtil.logOperation(conn, "SP_CREATE_T_CONT", adminUser, "Se creó tienda en SP por contingencia: " + codigo);
                        }
                    } else {
                        spData.put(SP_UUID_FIELD, rowUuid);
                        FieldValueSet fieldsToCreate = new FieldValueSet();
                        fieldsToCreate.setAdditionalData(spData);
                        SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fieldsToCreate);
                        LogUtil.logOperation(conn, "SP_CREATE_T", adminUser, "SP creado para nueva tienda: " + codigo);
                    }
                } catch (Exception e) {
                    LogUtil.logOperation(conn, "SP_REPLICATION_ERROR", adminUser, "Fallo en replicación de tienda " + codigo + ". Causa: " + e.getMessage());
                    e.printStackTrace();
                }
                
                conn.commit();
                sendJsonResponse(response, true, "Tienda guardada correctamente.", HttpServletResponse.SC_OK);

            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(response, false, "Error al guardar la tienda: " + e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, boolean success, String message, int statusCode) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);
        ObjectNode jsonResponse = objectMapper.createObjectNode();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}
