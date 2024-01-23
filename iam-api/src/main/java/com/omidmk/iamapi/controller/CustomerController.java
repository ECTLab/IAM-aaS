package com.omidmk.iamapi.controller;

import com.omidmk.iamapi.config.KeycloakProperties;
import com.omidmk.iamapi.controller.dto.*;
import com.omidmk.iamapi.exception.*;
import com.omidmk.iamapi.mapper.DeploymentMapper;
import com.omidmk.iamapi.mapper.TicketMapper;
import com.omidmk.iamapi.mapper.UserMapper;
import com.omidmk.iamapi.model.deployment.DeploymentModel;
import com.omidmk.iamapi.model.ticket.DialogModel;
import com.omidmk.iamapi.model.ticket.TicketModel;
import com.omidmk.iamapi.model.user.UserModel;
import com.omidmk.iamapi.oauth2.model.IAMUser;
import com.omidmk.iamapi.service.*;
import jakarta.validation.Valid;
import jakarta.ws.rs.QueryParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/customer")
@RequiredArgsConstructor
@Slf4j
@Validated
public class CustomerController {
    private final DeploymentService deploymentService;
    private final KeycloakService keycloakService;
    private final MailService mailService;
    private final TicketService ticketService;
    private final CustomerService customerService;
    private final DialogService dialogService;

    private final KeycloakProperties keycloakProperties;
    private final DeploymentMapper deploymentMapper;
    private final UserMapper userMapper;
    private final TicketMapper ticketMapper;

    @Value("${app.iam-aas.fail-on-mail-error:false}")
    private boolean failOnMailError;

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public Deployment createDeployment(@AuthenticationPrincipal IAMUser user, @RequestBody @Valid CreateDeploymentDTO requestBody) throws ApplicationException {
        if (user.getBalance() <= 0)
            throw new BalanceNotEnoughException();

        if (!isRealmAvailable(requestBody.getRealmName()))
            throw new RealmAlreadyExistException();

        var deployment = new DeploymentModel();
        try {
            deployment = deploymentService.createDeployment(user.getId(), requestBody.getRealmName(), requestBody.getPlan());
            final var passwordBytes = new byte[16];
            new SecureRandom().nextBytes(passwordBytes);
            final var username = "admin";
            final var password = new String(Base64.getEncoder().encode(passwordBytes));
            final var realmUrl = STR."\{keycloakProperties.getBaseUrl()}/admin/\{requestBody.getRealmName()}/console";
            keycloakService.createRealm(requestBody.getRealmName());
            keycloakService.createAdminUser(requestBody.getRealmName(), username, password, true);
            mailService.sendCustomerCredentials(user.getEmail(), username, password, realmUrl);
            deployment.setState(DeploymentModel.State.DEPLOYED);
        } catch (SendingMailFailedException ex) {
            log.error("Sending mail error occurred on creating deployment. {}", ex.getMessage(), ex);
            if (failOnMailError) {
                log.warn("Fail on mail error is on.");
                throw ex;
            }
            deployment.setState(DeploymentModel.State.DEPLOYED);
        } catch (ApplicationException ex) {
            log.error("Application Error occurred on creating deployment. {}", ex.getMessage(), ex);
            deployment.setState(DeploymentModel.State.FAILED_TO_DEPLOY);
            if (!(ex instanceof RealmAlreadyExistException))
                keycloakService.deleteRealm(requestBody.getRealmName());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Runtime Error occurred on creating deployment. {}", ex.getMessage(), ex);
            deployment.setState(DeploymentModel.State.FAILED_TO_DEPLOY);
            keycloakService.deleteRealm(requestBody.getRealmName());
            throw new InternalException(ex.getMessage());
        } finally {
            deployment = deploymentService.saveDeployment(deployment);
        }
        return deploymentMapper.deploymentModelToDeployment(deployment);
    }

    @GetMapping("/deployments")
    public List<Deployment> getDeployments(@AuthenticationPrincipal IAMUser user, @PageableDefault Pageable pageable) throws UserNotFoundException {
        Page<DeploymentModel> deployments = deploymentService.findDeploymentsOfUser(user.getId(), pageable);
        return deploymentMapper.deploymentModelListToDeploymentList(deployments.toList());
    }

    @GetMapping("/deployments/{deploymentId}")
    public Deployment getSingleDeployment(@AuthenticationPrincipal IAMUser user, @PathVariable UUID deploymentId) throws ApplicationException {
        Optional<DeploymentModel> deployment = deploymentService.findDeploymentOfUserById(user.getId(), deploymentId);
        if (deployment.isEmpty())
            throw new DeploymentNotFoundException();

        return deploymentMapper.deploymentModelToDeployment(deployment.get());
    }

    @PutMapping("/deployments/{deploymentId}")
    public Deployment updateDeployment(@AuthenticationPrincipal IAMUser user, @PathVariable UUID deploymentId, @RequestBody @Valid UpdateDeploymentDTO updateDeploymentRequest) throws ApplicationException{
        Optional<DeploymentModel> deployment = deploymentService.findDeploymentOfUserById(user.getId(), deploymentId);
        if (deployment.isEmpty())
            throw new DeploymentNotFoundException();

        DeploymentModel oldDeployment = deployment.get();
        oldDeployment.setPlan(updateDeploymentRequest.getPlan());
        return deploymentMapper.deploymentModelToDeployment(deploymentService.saveDeployment(oldDeployment));
    }

    @DeleteMapping("/deployments/{deploymentId}")
    public void deleteDeployment(@AuthenticationPrincipal IAMUser user, @PathVariable UUID deploymentId) throws ApplicationException {
        Optional<DeploymentModel> deployment = deploymentService.findDeploymentOfUserById(user.getId(), deploymentId);
        if (deployment.isEmpty())
            throw new DeploymentNotFoundException();

        keycloakService.deleteRealm(deployment.get().getRealmName());
        deploymentService.deleteDeployment(deploymentId);
    }

    @GetMapping("/deployments/available")
    public boolean isRealmAvailable(@QueryParam("realmName") String realmName) {
        return deploymentService.isRealmAvailable(realmName);
    }

    @GetMapping("/tickets")
    public List<Ticket> getAllTickets(@AuthenticationPrincipal IAMUser user, @PageableDefault Pageable pageable) throws ApplicationException {
        Page<TicketModel> allTickets = ticketService.findAllTicketsByUserId(user.getId(), pageable);
        return ticketMapper.ticketModelListToTicketList(allTickets.toList());
    }

    @GetMapping("/tickets/{ticketId}")
    public Ticket getSingleTicket(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId) throws ApplicationException {
        Optional<TicketModel> userTicket = ticketService.findUserTicketById(user.getId(), ticketId);
        if (userTicket.isEmpty())
            throw new TicketNotFoundException();

        return ticketMapper.ticketModelToTicket(userTicket.get());
    }

    @PostMapping("/tickets")
    public Ticket createNewTicket(@AuthenticationPrincipal IAMUser user, @RequestBody @Valid AddTicketDialogRequest dialogRequest) throws UserNotFoundException {
        UserModel userModel = userMapper.iamUserToUserModel(user);
        var dialog = new DialogModel(userModel, dialogRequest.getDialog());
        var ticket = new TicketModel();
        ticket.setCustomer(userModel);
        ticket.setState(TicketModel.State.WAITING_FOR_ADMIN_RESPONSE);
        ticket.setDialogs(List.of(dialog));
        userModel.getTickets().add(ticket);
        customerService.saveUser(userModel);
        return ticketMapper.ticketModelToTicket(ticket);
    }

    @PostMapping("/tickets/{ticketId}")
    public Ticket addDialogToTicket(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId, @RequestBody @Valid AddTicketDialogRequest dialogRequest) throws ApplicationException {
        TicketModel ticket = ticketService.findUserTicketById(user.getId(), ticketId).orElseThrow(TicketNotFoundException::new);
        UserModel userModel = customerService.findUserById(user.getId());

        var dialog = new DialogModel(userModel, dialogRequest.getDialog());
        ticket.getDialogs().add(dialog);
        ticket.setState(TicketModel.State.WAITING_FOR_ADMIN_RESPONSE);
        return ticketMapper.ticketModelToTicket(ticketService.saveTicket(ticket));
    }

    @DeleteMapping("/tickets/{ticketId}")
    public void deleteSingleTicket(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId) throws ApplicationException {
        Optional<TicketModel> ticket = ticketService.findUserTicketById(user.getId(), ticketId);
        if (ticket.isEmpty())
            throw new TicketNotFoundException();

        ticketService.deleteTicket(ticketId);
    }

    @GetMapping("tickets/{ticketId}/dialogs")
    public List<Dialog> getAllDialogsOfTicket(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId, @PageableDefault Pageable pageable) throws ApplicationException {
        // First, make sure this ticket belongs to this user
        Optional<TicketModel> ticket = ticketService.findUserTicketById(user.getId(), ticketId);
        if (ticket.isEmpty())
            throw new TicketNotFoundException();

        Page<DialogModel> dialogsOfTicket = dialogService.findDialogsOfTicket(ticket.get(), pageable);
        return ticketMapper.dialogModelListToDialogList(dialogsOfTicket.toList());
    }

    @GetMapping("tickets/{ticketId}/dialogs/{dialogId}")
    public Dialog getDialogOfTicket(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId, @PathVariable UUID dialogId) throws ApplicationException {
        Optional<TicketModel> ticket = ticketService.findUserTicketById(user.getId(), ticketId);
        if (ticket.isEmpty())
            throw new TicketNotFoundException();

        DialogModel dialogModel = ticket.get().getDialogs()
                .stream()
                .filter(dialog -> dialog.getId().equals(dialogId))
                .findFirst()
                .orElseThrow(DialogNotFoundException::new);

        return ticketMapper.dialogModelToDialog(dialogModel);
    }

    @DeleteMapping("ticket/{ticketId}/dialogs/{dialogId}")
    public void deleteSingleDialog(@AuthenticationPrincipal IAMUser user, @PathVariable UUID ticketId, @PathVariable UUID dialogId) throws ApplicationException {
        Optional<TicketModel> ticket = ticketService.findUserTicketById(user.getId(), ticketId);
        if (ticket.isEmpty())
            throw new TicketNotFoundException();

        ticket.get().getDialogs()
                .stream()
                .filter(dialog -> dialog.getId().equals(dialogId))
                .findFirst()
                .orElseThrow(DialogNotFoundException::new);

        ticketService.deleteDialog(dialogId);
    }

    @GetMapping("/userinfo")
    public Customer getUserInfo(@AuthenticationPrincipal IAMUser user) throws UserNotFoundException {
        UserModel userModel = customerService.findUserById(user.getId());
        return userMapper.userModelToCustomer(userModel);
    }
}
