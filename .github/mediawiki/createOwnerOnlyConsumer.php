<?php

require_once getenv( 'MW_INSTALL_PATH' ) . '/maintenance/Maintenance.php';

use MediaWiki\Context\RequestContext;
use MediaWiki\Extension\OAuth\Backend\Utils;
use MediaWiki\Extension\OAuth\Control\ConsumerSubmitControl;
use MediaWiki\User\User;

/**
 * Creates an owner-only OAuth 1.0a consumer for LocalOAuth1Test and prints its keys and the access token it
 * comes with, as JSON. The extension's own createOAuthConsumer.php takes no --ownerOnly before 1.45.
 */
class CreateOwnerOnlyConsumer extends Maintenance {
	public function __construct() {
		parent::__construct();
		$this->addOption( 'user', 'The account the consumer belongs to', true, true );
		$this->addOption( 'grants', 'A grant to give it', true, true, false, true );
		$this->requireExtension( 'OAuth' );
	}

	public function execute() {
		$user = User::newFromName( $this->getOption( 'user' ) );
		$data = [
			'action' => 'propose',
			'name' => 'kwikibot-oauth1-' . wfTimestampNow(),
			'version' => '1.0',
			'description' => 'Owner-only OAuth 1.0a consumer for the kwikibot live test',
			'callbackUrl' => 'http://localhost/',
			'oauthVersion' => 1,
			'callbackIsPrefix' => false,
			'grants' => json_encode( $this->getOption( 'grants' ) ),
			'granttype' => 'normal',
			'ownerOnly' => true,
			'oauth2IsConfidential' => true,
			'oauth2GrantTypes' => [ 'authorization_code', 'refresh_token' ],
			'email' => $user->getEmail(),
			'wiki' => '*',
			'rsaKey' => '',
			'agreement' => true,
			'restrictions' => MWRestrictions::newDefault(),
		];
		$context = RequestContext::getMain();
		$context->setUser( $user );
		$control = new ConsumerSubmitControl( $context, $data, Utils::getCentralDB( DB_PRIMARY ) );
		$status = $control->submit();
		if ( !$status->isGood() ) {
			$this->fatalError( $status->getMessage()->text() );
		}
		$consumer = $status->value['result']['consumer'];
		$token = $status->value['result']['accessToken'];
		$this->output( json_encode( [
			'consumerKey' => $consumer->getConsumerKey(),
			'consumerSecret' => Utils::hmacDBSecret( $consumer->getSecretKey() ),
			'accessToken' => $token->key,
			'accessSecret' => Utils::hmacDBSecret( $token->secret ),
		] ) . "\n" );
	}
}

$maintClass = CreateOwnerOnlyConsumer::class;
require_once RUN_MAINTENANCE_IF_MAIN;
