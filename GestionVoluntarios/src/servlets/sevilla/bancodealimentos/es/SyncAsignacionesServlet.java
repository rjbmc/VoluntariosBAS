package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.google.gson.JsonObject;
import com.microsoft.graph.models.FieldValueSet;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/sync-asignaciones")
public class SyncAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String SP_LIST_ASIGNACIONES = "Asignaciones";
    private static final String SP_LIST_VOLUNTARIOS = "Voluntarios";
    private static final String SP_LIST_TIENDAS = "Tiendas";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);
        StringBuilder errorLog = new StringBuilder();
        int fallidos = 0;
        int totalProcesados = 0;

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado. La sesión ha expirado. Por favor, cierra sesión y vuelve a entrar.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            String listIdAsignaciones = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_ASIGNACIONES);
            String listIdTiendas = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_TIENDAS);
            String listIdVoluntarios = SharepointUtil.getListId(SharepointUtil.SITE_ID, SP_LIST_VOLUNTARIOS);

            if (listIdAsignaciones == null) throw new Exception("La lista 'Asignaciones' no fue encontrada en SharePoint.");
            if (listIdTiendas == null) throw new Exception("La lista 'Tiendas' no fue encontrada en SharePoint.");
            if (listIdVoluntarios == null) throw new Exception("La lista 'Voluntarios' no fue encontrada en SharePoint.");

            SharepointUtil.deleteAllListItems(SharepointUtil.SITE_ID, listIdAsignaciones);
            Thread.sleep(1000); // Pausa prudencial

            PreparedStatement psVoluntariosCampana = conn.prepareStatement("SELECT * FROM voluntarios_en_campana");
            ResultSet rs = psVoluntariosCampana.executeQuery();

            while (rs.next()) {
                totalProcesados++;
                String voluntarioRowUuid = rs.getString("SqlRowUUID");
                String idVoluntarioDb = rs.getString("Usuario");

                try {
                    FieldValueSet fields = new FieldValueSet();
                    fields.getAdditionalData().put("SqlRowUUID", voluntarioRowUuid);
                    fields.getAdditionalData().put("Title", voluntarioRowUuid); // Title es obligatorio

                    // --- VOLUNTARIO ---
                    String voluntarioUuid = getLookupUuid(conn, "SELECT SqlRowUUID FROM voluntarios WHERE usuario = ?", idVoluntarioDb);
                    if (voluntarioUuid != null) {
                        String spVoluntarioId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listIdVoluntarios, "SqlRowUUID", voluntarioUuid);
                        if (spVoluntarioId != null) {
                            fields.getAdditionalData().put("UsuarioLookupId", spVoluntarioId);
                        } else {
                            throw new Exception("Voluntario con UUID " + voluntarioUuid + " no encontrado en SharePoint.");
                        }
                    } else {
                        throw new Exception("Voluntario con ID " + idVoluntarioDb + " no encontrado en la BD local.");
                    }
                    
                    fields.getAdditionalData().put("Campana", rs.getString("Campana"));

                    // --- TURNOS ---
                    for (int i = 1; i <= 4; i++) {
                        int idTiendaDb = rs.getInt("Turno" + i);
                        String comentario = rs.getString("Comentario" + i);

                        if (idTiendaDb > 0) {
                            String tiendaUuid = getLookupUuid(conn, "SELECT SqlRowUUID FROM tiendas WHERE codigo = ?", idTiendaDb);
                            if (tiendaUuid != null) {
                                String spTiendaId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listIdTiendas, "SqlRowUUID", tiendaUuid);
                                if (spTiendaId != null) {
                                    fields.getAdditionalData().put("Turno" + i + "LookupId", spTiendaId);
                                    fields.getAdditionalData().put("Comentario" + i, comentario);
                                } else {
                                    throw new Exception("Tienda para Turno"+i+" con UUID " + tiendaUuid + " no encontrada en SharePoint.");
                                }
                            } else {
                                throw new Exception("Tienda para Turno"+i+" con ID " + idTiendaDb + " no encontrada en la BD.");
                            }
                        }
                    }
                    
                    SharepointUtil.createListItem(SharepointUtil.SITE_ID, listIdAsignaciones, fields);

                } catch (Exception e) {
                    fallidos++;
                    errorLog.append("[FALLO] Fila de voluntario "+ idVoluntarioDb +" (UUID: " + voluntarioRowUuid + "). Causa: ").append(e.getMessage()).append("\n");
                }
            }
            
            rs.close();
            psVoluntariosCampana.close();

            jsonResponse.addProperty("success", fallidos == 0);
            jsonResponse.addProperty("message", "Sincronización de Asignaciones completada. Total: " + totalProcesados + ". Fallidos: " + fallidos + ". Errores: " + (errorLog.length() > 0 ? errorLog.toString() : "Ninguno"));
            
        } catch (Exception e) {
            e.printStackTrace();
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error CRÍTICO en SyncAsignacionesServlet: " + e.getMessage());
        } finally {
            response.getWriter().write(jsonResponse.toString());
        }
    }

    private String getLookupUuid(Connection conn, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param instanceof Integer) {
                ps.setInt(1, (Integer) param);
            } else {
                ps.setString(1, (String) param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}